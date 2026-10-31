package com.campus.platform.evaluation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.CourseSelection;
import com.campus.platform.course.entity.CourseTeacher;
import com.campus.platform.course.entity.SelectionStatus;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.mapper.CourseMapper;
import com.campus.platform.course.mapper.CourseSelectionMapper;
import com.campus.platform.course.mapper.CourseTeacherMapper;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.course.service.CourseAccessService;
import com.campus.platform.course.service.CourseService;
import com.campus.platform.evaluation.dto.EvaluationCommentView;
import com.campus.platform.evaluation.dto.EvaluationCourseSummary;
import com.campus.platform.evaluation.dto.EvaluationOverview;
import com.campus.platform.evaluation.dto.EvaluationSubmitRequest;
import com.campus.platform.evaluation.dto.EvaluationTaskView;
import com.campus.platform.evaluation.entity.TeachingEvaluation;
import com.campus.platform.evaluation.mapper.TeachingEvaluationMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.setting.service.SettingService;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学生评教：学生按课程与教师打分，教师只看聚合结果与匿名留言。
 */
@Service
public class EvaluationService {

    static final String MODULE = "evaluation";

    private final TeachingEvaluationMapper evaluationMapper;
    private final CourseSelectionMapper selectionMapper;
    private final CourseTeacherMapper teacherMapper;
    private final CourseMapper courseMapper;
    private final SemesterMapper semesterMapper;
    private final SysUserMapper userMapper;
    private final CourseService courseService;
    private final CourseAccessService accessService;
    private final SettingService settingService;
    private final AuditService auditService;

    public EvaluationService(TeachingEvaluationMapper evaluationMapper, CourseSelectionMapper selectionMapper,
                             CourseTeacherMapper teacherMapper, CourseMapper courseMapper,
                             SemesterMapper semesterMapper, SysUserMapper userMapper, CourseService courseService,
                             CourseAccessService accessService, SettingService settingService,
                             AuditService auditService) {
        this.evaluationMapper = evaluationMapper;
        this.selectionMapper = selectionMapper;
        this.teacherMapper = teacherMapper;
        this.courseMapper = courseMapper;
        this.semesterMapper = semesterMapper;
        this.userMapper = userMapper;
        this.courseService = courseService;
        this.accessService = accessService;
        this.settingService = settingService;
        this.auditService = auditService;
    }

    /** 我的评教任务：本人已选课程 × 授课教师，标记是否已评。 */
    public List<EvaluationTaskView> tasks(AuthenticatedUser user) {
        Permissions.require(user, Permissions.EVALUATION_SUBMIT);
        List<Long> courseIds = selectionMapper.selectActiveCourseIds(user.userId());
        if (courseIds.isEmpty()) {
            return List.of();
        }
        List<Course> courses = courseMapper.selectBatchIds(courseIds).stream()
                .sorted(Comparator.comparing(Course::getSemesterId).reversed()
                        .thenComparing(Course::getCode))
                .toList();
        if (courses.isEmpty()) {
            return List.of();
        }
        Map<Long, Semester> semesters = new HashMap<>();
        semesterMapper.selectBatchIds(courses.stream().map(Course::getSemesterId).collect(Collectors.toSet()))
                .forEach(s -> semesters.put(s.getId(), s));
        List<CourseTeacher> relations = teacherMapper.selectByCourses(
                courses.stream().map(Course::getId).toList());
        Map<Long, List<Long>> teacherIdsByCourse = new LinkedHashMap<>();
        relations.forEach(r -> teacherIdsByCourse.computeIfAbsent(r.getCourseId(), k -> new ArrayList<>())
                .add(r.getTeacherId()));
        Set<Long> teacherIds = relations.stream().map(CourseTeacher::getTeacherId).collect(Collectors.toSet());
        Map<Long, SysUser> teachers = new HashMap<>();
        if (!teacherIds.isEmpty()) {
            userMapper.selectBatchIds(teacherIds).forEach(u -> teachers.put(u.getId(), u));
        }
        Map<String, TeachingEvaluation> mine = new HashMap<>();
        evaluationMapper.selectList(new LambdaQueryWrapper<TeachingEvaluation>()
                        .eq(TeachingEvaluation::getStudentId, user.userId()))
                .forEach(e -> mine.put(key(e.getCourseId(), e.getTeacherId()), e));

        List<EvaluationTaskView> tasks = new ArrayList<>();
        for (Course course : courses) {
            Semester semester = semesters.get(course.getSemesterId());
            for (Long teacherId : teacherIdsByCourse.getOrDefault(course.getId(), List.of())) {
                SysUser teacher = teachers.get(teacherId);
                TeachingEvaluation done = mine.get(key(course.getId(), teacherId));
                tasks.add(new EvaluationTaskView(course.getId(), course.getCode(), course.getName(),
                        course.getSemesterId(), semester == null ? null : semester.getName(),
                        teacherId, teacher == null ? null : teacher.getDisplayName(),
                        done != null, done == null ? null : done.getTotalScore()));
            }
        }
        return tasks;
    }

    @Transactional
    public EvaluationTaskView submit(AuthenticatedUser user, EvaluationSubmitRequest req) {
        Permissions.require(user, Permissions.EVALUATION_SUBMIT);
        Course course = courseService.requireCourse(req.courseId());
        if (selectionMapper.selectActive(req.courseId(), user.userId()) == null) {
            throw BusinessException.forbidden("只能评价本人已选课程");
        }
        if (teacherMapper.selectOne(req.courseId(), req.teacherId()) == null) {
            throw BusinessException.badRequest("该教师不在此课程授课");
        }
        BigDecimal total = BigDecimal.valueOf(req.scoreTeaching() + req.scoreContent()
                        + req.scoreInteraction() + req.scoreWorkload())
                .divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP);

        TeachingEvaluation evaluation = new TeachingEvaluation();
        evaluation.setSemesterId(course.getSemesterId());
        evaluation.setCourseId(course.getId());
        evaluation.setTeacherId(req.teacherId());
        evaluation.setStudentId(user.userId());
        evaluation.setScoreTeaching(req.scoreTeaching());
        evaluation.setScoreContent(req.scoreContent());
        evaluation.setScoreInteraction(req.scoreInteraction());
        evaluation.setScoreWorkload(req.scoreWorkload());
        evaluation.setTotalScore(total);
        evaluation.setComment(req.comment() == null || req.comment().isBlank() ? null : req.comment().trim());
        evaluation.setAnonymous(req.anonymous() != null
                ? req.anonymous()
                : settingService.getBool(SettingService.EVALUATION_ANONYMOUS_KEY, true));
        try {
            evaluationMapper.insert(evaluation);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("这门课你已经评过了");
        }
        auditService.record(AuditEvent.builder(MODULE, "EVALUATION_SUBMIT")
                .target("COURSE", course.getId())
                .detail("teacherId", req.teacherId())
                .detail("totalScore", total)
                .build());
        SysUser teacher = userMapper.selectById(req.teacherId());
        return new EvaluationTaskView(course.getId(), course.getCode(), course.getName(), course.getSemesterId(),
                null, req.teacherId(), teacher == null ? null : teacher.getDisplayName(), true, total);
    }

    /** 单门课程的评教聚合：任课教师看本人，管理员看全部。 */
    public EvaluationCourseSummary courseSummary(AuthenticatedUser user, long courseId) {
        Permissions.require(user, Permissions.EVALUATION_READ);
        accessService.requireRosterViewable(user, courseId);
        Course course = courseService.requireCourse(courseId);
        Semester semester = semesterMapper.selectById(course.getSemesterId());
        List<TeachingEvaluation> rows = evaluationMapper.selectList(new LambdaQueryWrapper<TeachingEvaluation>()
                .eq(TeachingEvaluation::getCourseId, courseId));
        Long filter = accessService.isTeacherOf(user, courseId) ? user.userId() : null;
        return summaryOf(course, semester, filter, rows);
    }

    /** 教师的评教总览：本人任教课程的全部聚合。 */
    public EvaluationOverview myOverview(AuthenticatedUser user) {
        Permissions.require(user, Permissions.EVALUATION_READ);
        List<Long> courseIds = teacherMapper.selectCourseIdsByTeachers(List.of(user.userId()));
        if (courseIds.isEmpty()) {
            return new EvaluationOverview(0, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), List.of());
        }
        List<Course> courses = courseMapper.selectBatchIds(courseIds);
        Map<Long, Semester> semesters = new HashMap<>();
        semesterMapper.selectBatchIds(courses.stream().map(Course::getSemesterId).collect(Collectors.toSet()))
                .forEach(s -> semesters.put(s.getId(), s));
        List<TeachingEvaluation> rows = evaluationMapper.selectList(new LambdaQueryWrapper<TeachingEvaluation>()
                .in(TeachingEvaluation::getCourseId, courseIds));
        Map<Long, List<TeachingEvaluation>> byCourse = rows.stream()
                .collect(Collectors.groupingBy(TeachingEvaluation::getCourseId));

        List<EvaluationCourseSummary> summaries = new ArrayList<>();
        for (Course course : courses) {
            List<TeachingEvaluation> courseRows = byCourse.getOrDefault(course.getId(), List.of());
            if (courseRows.isEmpty()) {
                continue;
            }
            summaries.add(summaryOf(course, semesters.get(course.getSemesterId()), user.userId(), courseRows));
        }
        summaries.sort(Comparator.comparing(EvaluationCourseSummary::avgTotal).reversed());
        BigDecimal avg = rows.isEmpty() ? BigDecimal.ZERO
                : rows.stream().map(TeachingEvaluation::getTotalScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
        return new EvaluationOverview(rows.size(), avg, summaries);
    }

    private EvaluationCourseSummary summaryOf(Course course, Semester semester, Long teacherFilter,
                                              List<TeachingEvaluation> rows) {
        // 一门课可能有多位教师：教师只看到自己的评分，管理员看到全部
        List<TeachingEvaluation> filtered = teacherFilter == null ? rows : rows.stream()
                .filter(r -> Objects.equals(r.getTeacherId(), teacherFilter))
                .toList();
        int count = filtered.size();
        List<EvaluationCommentView> comments = filtered.stream()
                .filter(r -> r.getComment() != null && !r.getComment().isBlank())
                .sorted(Comparator.comparing(TeachingEvaluation::getCreatedAt).reversed())
                .limit(20)
                .map(r -> new EvaluationCommentView(r.getComment(), r.getTotalScore(), r.getCreatedAt()))
                .toList();
        return new EvaluationCourseSummary(course.getId(), course.getCode(), course.getName(),
                course.getSemesterId(), semester == null ? null : semester.getName(), count,
                avg(filtered, TeachingEvaluation::getScoreTeaching),
                avg(filtered, TeachingEvaluation::getScoreContent),
                avg(filtered, TeachingEvaluation::getScoreInteraction),
                avg(filtered, TeachingEvaluation::getScoreWorkload),
                avgTotal(filtered),
                comments);
    }

    private static BigDecimal avg(List<TeachingEvaluation> rows,
                                  java.util.function.Function<TeachingEvaluation, Integer> getter) {
        if (rows.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal sum = rows.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal avgTotal(List<TeachingEvaluation> rows) {
        if (rows.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal sum = rows.stream().map(TeachingEvaluation::getTotalScore)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
    }

    private static String key(Long courseId, Long teacherId) {
        return courseId + ":" + teacherId;
    }
}
