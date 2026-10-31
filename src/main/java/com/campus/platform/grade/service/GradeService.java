package com.campus.platform.grade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.CourseSelection;
import com.campus.platform.course.entity.SelectionStatus;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.mapper.CourseMapper;
import com.campus.platform.course.mapper.CourseSelectionMapper;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.course.service.CourseAccessService;
import com.campus.platform.course.service.CourseService;
import com.campus.platform.grade.dto.GpaSummary;
import com.campus.platform.grade.dto.GradeBookView;
import com.campus.platform.grade.dto.GradeEntryItem;
import com.campus.platform.grade.dto.GradeRosterItem;
import com.campus.platform.grade.dto.GradeRuleView;
import com.campus.platform.grade.dto.GradeView;
import com.campus.platform.grade.dto.SemesterGradeView;
import com.campus.platform.grade.dto.TranscriptView;
import com.campus.platform.grade.entity.CourseGrade;
import com.campus.platform.grade.entity.GradeRule;
import com.campus.platform.grade.entity.GradeStatus;
import com.campus.platform.grade.mapper.CourseGradeMapper;
import com.campus.platform.grade.mapper.GradeRuleMapper;
import com.campus.platform.notification.entity.NotificationCategory;
import com.campus.platform.notification.entity.NotificationLevel;
import com.campus.platform.notification.service.NotificationService;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 成绩与绩点。
 * <ul>
 *   <li>学生只看已发布成绩；绩点与加权平均按学分计算，规则来自 {@code grade_rule}；</li>
 *   <li>任课教师（主讲或助教）可录入、发布本人课程成绩，管理员可改全部；</li>
 *   <li>已发布成绩对教师只读，需管理员调整，避免学期中途成绩反复变动。</li>
 * </ul>
 */
@Service
public class GradeService {

    static final String MODULE = "grade";

    private final CourseGradeMapper gradeMapper;
    private final GradeRuleMapper ruleMapper;
    private final CourseMapper courseMapper;
    private final SemesterMapper semesterMapper;
    private final CourseSelectionMapper selectionMapper;
    private final SysUserMapper userMapper;
    private final SysOrgUnitMapper orgMapper;
    private final CourseService courseService;
    private final CourseAccessService accessService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    public GradeService(CourseGradeMapper gradeMapper, GradeRuleMapper ruleMapper, CourseMapper courseMapper,
                        SemesterMapper semesterMapper, CourseSelectionMapper selectionMapper,
                        SysUserMapper userMapper, SysOrgUnitMapper orgMapper, CourseService courseService,
                        CourseAccessService accessService, NotificationService notificationService,
                        AuditService auditService, Clock clock) {
        this.gradeMapper = gradeMapper;
        this.ruleMapper = ruleMapper;
        this.courseMapper = courseMapper;
        this.semesterMapper = semesterMapper;
        this.selectionMapper = selectionMapper;
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.courseService = courseService;
        this.accessService = accessService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /* ---------------- 学生视角 ---------------- */

    public TranscriptView transcript(AuthenticatedUser user) {
        Permissions.require(user, Permissions.GRADE_READ);
        List<CourseGrade> rows = gradeMapper.selectPublishedByStudent(user.userId());
        if (rows.isEmpty()) {
            GpaSummary zero = new GpaSummary(scale(BigDecimal.ZERO), scale(BigDecimal.ZERO),
                    scale(BigDecimal.ZERO), scale(BigDecimal.ZERO), 0);
            return new TranscriptView(zero, null, List.of());
        }
        Set<Long> courseIds = new HashSet<>();
        rows.forEach(r -> courseIds.add(r.getCourseId()));
        Map<Long, Course> courses = new HashMap<>();
        courseMapper.selectBatchIds(courseIds).forEach(c -> courses.put(c.getId(), c));

        Set<Long> semesterIds = new HashSet<>();
        courses.values().forEach(c -> semesterIds.add(c.getSemesterId()));
        Map<Long, Semester> semesters = new HashMap<>();
        if (!semesterIds.isEmpty()) {
            semesterMapper.selectBatchIds(semesterIds).forEach(s -> semesters.put(s.getId(), s));
        }

        // 按学期分组，学期按开始日期倒序（最近的在最前）
        Map<Long, List<GradeView>> grouped = new LinkedHashMap<>();
        Map<Long, Semester> usedSemesters = new HashMap<>();
        for (CourseGrade row : rows) {
            Course course = courses.get(row.getCourseId());
            if (course == null) {
                continue;
            }
            Semester semester = semesters.get(course.getSemesterId());
            if (semester != null) {
                usedSemesters.put(semester.getId(), semester);
            }
            grouped.computeIfAbsent(course.getSemesterId(), k -> new ArrayList<>())
                    .add(new GradeView(row.getId(), course.getId(), course.getCode(), course.getName(),
                            creditOf(course), course.getSemesterId(),
                            semester == null ? null : semester.getCode(),
                            semester == null ? null : semester.getName(),
                            row.getScore(), row.getGradePoint(), row.getGradeLevel(), row.getStatus(),
                            row.getRemark(), row.getPublishedAt()));
        }

        List<SemesterGradeView> semesterViews = new ArrayList<>();
        for (Map.Entry<Long, List<GradeView>> entry : grouped.entrySet()) {
            Semester semester = usedSemesters.get(entry.getKey());
            semesterViews.add(new SemesterGradeView(entry.getKey(),
                    semester == null ? null : semester.getCode(),
                    semester == null ? null : semester.getName(),
                    summarize(entry.getValue()),
                    entry.getValue()));
        }
        semesterViews.sort(Comparator.comparing(
                (SemesterGradeView v) -> {
                    Semester s = semesters.get(v.semesterId());
                    return s == null || s.getStartDate() == null ? java.time.LocalDate.MIN : s.getStartDate();
                }).reversed());

        List<GradeView> all = semesterViews.stream().flatMap(v -> v.grades().stream()).toList();
        return new TranscriptView(summarize(all), semesterViews.isEmpty() ? null : semesterViews.get(0), semesterViews);
    }

    private GpaSummary summarize(List<GradeView> grades) {
        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal pointCredits = BigDecimal.ZERO;
        BigDecimal scoreCredits = BigDecimal.ZERO;
        BigDecimal earned = BigDecimal.ZERO;
        int counted = 0;
        for (GradeView g : grades) {
            if (g.score() == null || g.credit() == null) {
                continue;
            }
            BigDecimal credit = g.credit();
            BigDecimal point = g.gradePoint() == null ? BigDecimal.ZERO : g.gradePoint();
            credits = credits.add(credit);
            pointCredits = pointCredits.add(point.multiply(credit));
            scoreCredits = scoreCredits.add(g.score().multiply(credit));
            if (point.compareTo(BigDecimal.ZERO) > 0) {
                earned = earned.add(credit);
            }
            counted++;
        }
        if (credits.compareTo(BigDecimal.ZERO) == 0) {
            return new GpaSummary(scale(BigDecimal.ZERO), scale(BigDecimal.ZERO), scale(BigDecimal.ZERO),
                    scale(BigDecimal.ZERO), counted);
        }
        return new GpaSummary(
                pointCredits.divide(credits, 2, RoundingMode.HALF_UP),
                scoreCredits.divide(credits, 2, RoundingMode.HALF_UP),
                scale(credits), scale(earned), counted);
    }

    /* ---------------- 教师视角 ---------------- */

    public GradeBookView gradeBook(AuthenticatedUser user, long courseId) {
        Permissions.requireAny(user, Permissions.GRADE_MANAGE, Permissions.GRADE_MANAGE_ALL, Permissions.GRADE_READ);
        Course course = courseService.requireCourse(courseId);
        accessService.requireRosterViewable(user, courseId);

        List<CourseSelection> selections = selectionMapper.selectList(new LambdaQueryWrapper<CourseSelection>()
                .eq(CourseSelection::getCourseId, courseId)
                .eq(CourseSelection::getStatus, SelectionStatus.SELECTED)
                .orderByAsc(CourseSelection::getId));
        Map<Long, SysUser> students = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        selections.forEach(s -> ids.add(s.getStudentId()));
        if (!ids.isEmpty()) {
            userMapper.selectBatchIds(ids).forEach(u -> students.put(u.getId(), u));
        }
        Map<Long, String> orgNames = new HashMap<>();
        Set<Long> orgIds = new HashSet<>();
        students.values().forEach(u -> {
            if (u.getOrgUnitId() != null) {
                orgIds.add(u.getOrgUnitId());
            }
        });
        if (!orgIds.isEmpty()) {
            orgMapper.selectBatchIds(orgIds).forEach(o -> orgNames.put(o.getId(), o.getName()));
        }
        Map<Long, CourseGrade> grades = new HashMap<>();
        boolean published = false;
        for (CourseGrade grade : gradeMapper.selectByCourse(courseId)) {
            grades.put(grade.getStudentId(), grade);
            published |= grade.getStatus() == GradeStatus.PUBLISHED;
        }
        Semester semester = semesterMapper.selectById(course.getSemesterId());

        List<GradeRosterItem> items = new ArrayList<>();
        for (CourseSelection selection : selections) {
            SysUser student = students.get(selection.getStudentId());
            CourseGrade grade = grades.get(selection.getStudentId());
            items.add(new GradeRosterItem(selection.getStudentId(),
                    student == null ? null : student.getUsername(),
                    student == null ? null : student.getDisplayName(),
                    student == null ? null : orgNames.get(student.getOrgUnitId()),
                    grade == null ? null : grade.getScore(),
                    grade == null ? null : grade.getGradePoint(),
                    grade == null ? null : grade.getGradeLevel(),
                    grade == null ? GradeStatus.DRAFT : grade.getStatus(),
                    grade == null ? null : grade.getRemark()));
        }
        return new GradeBookView(course.getId(), course.getCode(), course.getName(), creditOf(course),
                course.getSemesterId(), semester == null ? null : semester.getName(), published, items);
    }

    @Transactional
    public GradeBookView save(AuthenticatedUser user, long courseId, List<GradeEntryItem> items) {
        Permissions.requireAny(user, Permissions.GRADE_MANAGE, Permissions.GRADE_MANAGE_ALL);
        Course course = courseService.requireCourse(courseId);
        accessService.requireRosterViewable(user, courseId);
        boolean admin = user.hasPermission(Permissions.GRADE_MANAGE_ALL);

        Set<Long> enrolled = new HashSet<>();
        selectionMapper.selectList(new LambdaQueryWrapper<CourseSelection>()
                        .eq(CourseSelection::getCourseId, courseId)
                        .eq(CourseSelection::getStatus, SelectionStatus.SELECTED))
                .forEach(s -> enrolled.add(s.getStudentId()));

        List<GradeRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<GradeRule>()
                .orderByAsc(GradeRule::getSortOrder));
        for (GradeEntryItem item : items) {
            if (!enrolled.contains(item.studentId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "学生不在该课程的选课名单中");
            }
            CourseGrade existing = gradeMapper.selectOneByCourseAndStudent(courseId, item.studentId());
            if (existing != null && existing.getStatus() == GradeStatus.PUBLISHED && !admin) {
                throw BusinessException.rule("成绩已发布，如需修改请联系管理员");
            }
            GradeRule rule = item.score() == null ? null : matchRule(rules, item.score());
            if (existing == null) {
                existing = new CourseGrade();
                existing.setCourseId(courseId);
                existing.setStudentId(item.studentId());
                existing.setStatus(GradeStatus.DRAFT);
                existing.setVersion(0);
                applyScore(existing, item, rule, user.userId());
                gradeMapper.insert(existing);
            } else {
                if (existing.getStatus() == GradeStatus.PUBLISHED) {
                    existing.setStatus(GradeStatus.DRAFT);
                    existing.setPublishedAt(null);
                }
                applyScore(existing, item, rule, user.userId());
                gradeMapper.updateById(existing);
            }
        }
        audit("GRADE_SAVE", courseId, "courseCode", course.getCode(), "count", items.size());
        return gradeBook(user, courseId);
    }

    @Transactional
    public GradeBookView publish(AuthenticatedUser user, long courseId) {
        Permissions.requireAny(user, Permissions.GRADE_MANAGE, Permissions.GRADE_MANAGE_ALL);
        Course course = courseService.requireCourse(courseId);
        accessService.requireRosterViewable(user, courseId);

        List<CourseSelection> selections = selectionMapper.selectList(new LambdaQueryWrapper<CourseSelection>()
                .eq(CourseSelection::getCourseId, courseId)
                .eq(CourseSelection::getStatus, SelectionStatus.SELECTED));
        Map<Long, CourseGrade> grades = new HashMap<>();
        gradeMapper.selectByCourse(courseId).forEach(g -> grades.put(g.getStudentId(), g));
        List<String> missing = new ArrayList<>();
        for (CourseSelection selection : selections) {
            CourseGrade grade = grades.get(selection.getStudentId());
            if (grade == null || grade.getScore() == null) {
                SysUser student = userMapper.selectById(selection.getStudentId());
                missing.add(student == null ? String.valueOf(selection.getStudentId())
                        : student.getDisplayName() + "(" + student.getUsername() + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw BusinessException.rule("还有 " + missing.size() + " 名学生未录入成绩："
                    + String.join("、", missing.subList(0, Math.min(5, missing.size())))
                    + (missing.size() > 5 ? " 等" : ""));
        }
        gradeMapper.publishCourse(courseId, user.userId(), LocalDateTime.now(clock));
        audit("GRADE_PUBLISH", courseId, "courseCode", course.getCode());
        notificationService.notifyAll(selections.stream().map(CourseSelection::getStudentId).toList(),
                NotificationCategory.GRADE, NotificationLevel.SUCCESS, "成绩已发布",
                "《" + course.getName() + "》的成绩已发布，可在「成绩」中查看。",
                "/grades", "COURSE_GRADE", courseId);
        return gradeBook(user, courseId);
    }

    public List<GradeRuleView> rules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<GradeRule>()
                        .orderByAsc(GradeRule::getSortOrder))
                .stream()
                .map(r -> new GradeRuleView(r.getId(), r.getName(), r.getMinScore(), r.getMaxScore(),
                        r.getGradePoint(), r.getGradeLevel()))
                .toList();
    }

    /** 分数 → 规则；无匹配时抛错（避免静默写错绩点）。 */
    public GradeRule matchRule(List<GradeRule> rules, BigDecimal score) {
        return rules.stream()
                .filter(r -> r.covers(score))
                .max(Comparator.comparing(GradeRule::getMinScore))
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "没有匹配的绩点规则"));
    }

    private void applyScore(CourseGrade grade, GradeEntryItem item, GradeRule rule, Long operatorId) {
        grade.setScore(item.score() == null ? null : item.score().setScale(2, RoundingMode.HALF_UP));
        grade.setGradePoint(rule == null ? null : rule.getGradePoint());
        grade.setGradeLevel(rule == null ? null : rule.getGradeLevel());
        grade.setRemark(item.remark() == null || item.remark().isBlank() ? null : item.remark().trim());
        grade.setGradedBy(operatorId);
    }

    private static BigDecimal creditOf(Course course) {
        return course.getCredit() == null ? BigDecimal.ZERO : course.getCredit();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("COURSE", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
