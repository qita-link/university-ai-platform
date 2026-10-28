package com.campus.platform.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageQuery;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.dto.RosterItem;
import com.campus.platform.course.dto.SelectionView;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.CourseSchedule;
import com.campus.platform.course.entity.CourseSelection;
import com.campus.platform.course.entity.CourseStatus;
import com.campus.platform.course.entity.SelectionStatus;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.mapper.CourseMapper;
import com.campus.platform.course.mapper.CourseScheduleMapper;
import com.campus.platform.course.mapper.CourseSelectionMapper;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 选课与退课。
 * <p>
 * 并发控制分三步，缺一不可：
 * <ol>
 *   <li><b>按学生加锁</b>：事务开始先 {@code SELECT ... FROM sys_user WHERE id=? FOR UPDATE}，
 *       把同一学生的“查冲突 → 写选课”串行化，避免并发选两门互相冲突的课程时双方都通过检查；</li>
 *   <li><b>锁定课程行</b>：随后锁定课程行，与停开课程、容量变更串行化，保证停开后不会再有新选课；</li>
 *   <li><b>条件更新占名额</b>：{@code UPDATE course SET selected_count = selected_count + 1
 *       WHERE id=? AND status='OPEN' AND selected_count < capacity}，由数据库保证不超卖；</li>
 *   <li><b>唯一约束兜底</b>：{@code uk_course_selection_active} 保证同一学生同一课程只有一条有效记录。</li>
 * </ol>
 * 退课使用条件更新（{@code status='SELECTED'}）保证不会重复扣减容量。
 */
@Service
public class CourseSelectionService {

    static final String MODULE = "course";

    private final CourseSelectionMapper selectionMapper;
    private final CourseMapper courseMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final SemesterMapper semesterMapper;
    private final SemesterService semesterService;
    private final CourseService courseService;
    private final CourseAccessService accessService;
    private final SysUserMapper userMapper;
    private final SysOrgUnitMapper orgMapper;
    private final AuditService auditService;
    private final Clock clock;

    public CourseSelectionService(CourseSelectionMapper selectionMapper, CourseMapper courseMapper,
                                  CourseScheduleMapper scheduleMapper, SemesterMapper semesterMapper,
                                  SemesterService semesterService, CourseService courseService,
                                  CourseAccessService accessService, SysUserMapper userMapper,
                                  SysOrgUnitMapper orgMapper, AuditService auditService, Clock clock) {
        this.selectionMapper = selectionMapper;
        this.courseMapper = courseMapper;
        this.scheduleMapper = scheduleMapper;
        this.semesterMapper = semesterMapper;
        this.semesterService = semesterService;
        this.courseService = courseService;
        this.accessService = accessService;
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public SelectionView select(AuthenticatedUser user, long courseId) {
        Permissions.require(user, Permissions.SELECTION_SELECT);
        // 1) 学生维度串行化：同一学生的选课/退课互斥
        userMapper.lockById(user.userId());
        // 2) 课程维度串行化：与停开、容量变更互斥
        Course course = courseMapper.selectByIdForUpdate(courseId);
        if (course == null || course.getStatus() == CourseStatus.CANCELLED) {
            throw BusinessException.notFound("课程");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Semester semester = semesterService.require(course.getSemesterId());
        if (!semester.selectionOpen(now)) {
            throw new BusinessException(ErrorCode.SELECTION_WINDOW_CLOSED,
                    "当前不在选课时间窗口内（" + semester.getSelectionStartAt() + " ~ "
                            + semester.getSelectionEndAt() + "）");
        }
        if (!course.getStatus().acceptsSelection()) {
            throw new BusinessException(ErrorCode.SELECTION_COURSE_NOT_OPEN);
        }
        if (selectionMapper.selectActive(courseId, user.userId()) != null) {
            throw new BusinessException(ErrorCode.SELECTION_DUPLICATED);
        }
        CourseSchedule conflict = findConflict(user.userId(), course);
        if (conflict != null) {
            Course other = courseMapper.selectById(conflict.getCourseId());
            throw new BusinessException(ErrorCode.SELECTION_TIME_CONFLICT,
                    "与已选课程《" + (other == null ? conflict.getCourseId() : other.getName()) + "》时间冲突");
        }
        // 3) 条件更新占名额，数据库层面保证不超卖
        if (courseMapper.incrementSelectedCount(courseId, now) != 1) {
            throw new BusinessException(ErrorCode.SELECTION_COURSE_FULL);
        }

        CourseSelection selection = new CourseSelection();
        selection.setCourseId(courseId);
        selection.setStudentId(user.userId());
        selection.setStatus(SelectionStatus.SELECTED);
        selection.setSelectedAt(now);
        try {
            selectionMapper.insert(selection);
        } catch (DuplicateKeyException ex) {
            // 唯一约束兜底：理论上已被上面的锁与查重拦住，这里防御性转换
            throw new BusinessException(ErrorCode.SELECTION_DUPLICATED);
        }
        audit("COURSE_SELECTION_CREATE", selection.getId(), "courseId", courseId,
                "courseCode", course.getCode());
        return view(selection, course, semester, true);
    }

    @Transactional
    public void drop(AuthenticatedUser user, long selectionId) {
        Permissions.require(user, Permissions.SELECTION_SELECT);
        userMapper.lockById(user.userId());
        CourseSelection selection = selectionMapper.selectById(selectionId);
        if (selection == null || !Objects.equals(selection.getStudentId(), user.userId())
                || selection.getStatus() != SelectionStatus.SELECTED) {
            throw BusinessException.notFound("选课记录");
        }
        Course course = courseMapper.selectByIdForUpdate(selection.getCourseId());
        if (course == null) {
            throw BusinessException.notFound("选课记录");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Semester semester = semesterService.require(course.getSemesterId());
        if (!semester.selectionOpen(now)) {
            throw new BusinessException(ErrorCode.SELECTION_WINDOW_CLOSED, "当前不在选课时间窗口内，不能退课");
        }
        if (!course.getStatus().acceptsSelection()) {
            throw new BusinessException(ErrorCode.SELECTION_COURSE_NOT_OPEN, "课程已关闭，不能退课");
        }
        if (selectionMapper.markDropped(selectionId, user.userId(), user.userId(), now) != 1) {
            throw BusinessException.conflict("选课记录状态已变化，请刷新后重试");
        }
        courseMapper.decrementSelectedCount(course.getId(), now);
        audit("COURSE_SELECTION_DROP", selectionId, "courseId", course.getId(), "courseCode", course.getCode());
    }

    /** 本人已选课程；{@code semesterId} 为空时返回全部学期。 */
    public List<SelectionView> mySelections(AuthenticatedUser user, Long semesterId) {
        Permissions.require(user, Permissions.COURSE_READ);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        List<CourseSelection> selections = selectionMapper.selectList(new LambdaQueryWrapper<CourseSelection>()
                .eq(CourseSelection::getStudentId, user.userId())
                .eq(CourseSelection::getStatus, SelectionStatus.SELECTED)
                .orderByDesc(CourseSelection::getSelectedAt)
                .orderByDesc(CourseSelection::getId));
        if (selections.isEmpty()) {
            return List.of();
        }
        Set<Long> courseIds = new LinkedHashSet<>();
        selections.forEach(s -> courseIds.add(s.getCourseId()));
        Map<Long, Course> courses = new HashMap<>();
        courseMapper.selectBatchIds(courseIds).forEach(c -> courses.put(c.getId(), c));
        Map<Long, String> semesterNames = semesterNames(courses.values().stream()
                .map(Course::getSemesterId).toList());
        Map<Long, List<String>> teacherNames = courseService.teacherNamesOf(courseIds);

        List<SelectionView> result = new ArrayList<>();
        for (CourseSelection selection : selections) {
            Course course = courses.get(selection.getCourseId());
            if (course == null || course.getStatus() == CourseStatus.CANCELLED) {
                continue;
            }
            if (semesterId != null && !Objects.equals(course.getSemesterId(), semesterId)) {
                continue;
            }
            result.add(view(selection, course, semesterNames.get(course.getSemesterId()),
                    teacherNames.getOrDefault(course.getId(), List.of())));
        }
        return result;
    }

    /** 课程选课名单：任课教师或管理员可看，只返回有效选课。 */
    public PageResult<RosterItem> roster(AuthenticatedUser user, long courseId, PageQuery q) {
        Permissions.require(user, Permissions.COURSE_READ);
        courseService.requireCourse(courseId);
        accessService.requireRosterViewable(user, courseId);

        Page<CourseSelection> pageRequest = q.toPage();
        IPage<CourseSelection> page = selectionMapper.selectPage(pageRequest,
                new LambdaQueryWrapper<CourseSelection>()
                        .eq(CourseSelection::getCourseId, courseId)
                        .eq(CourseSelection::getStatus, SelectionStatus.SELECTED)
                        .orderByAsc(CourseSelection::getSelectedAt)
                        .orderByAsc(CourseSelection::getId));
        List<CourseSelection> records = page.getRecords();
        Map<Long, SysUser> students = new HashMap<>();
        Map<Long, String> orgNames = new HashMap<>();
        if (!records.isEmpty()) {
            Set<Long> studentIds = new HashSet<>();
            records.forEach(r -> studentIds.add(r.getStudentId()));
            userMapper.selectBatchIds(studentIds).forEach(u -> students.put(u.getId(), u));
            Set<Long> orgIds = new HashSet<>();
            students.values().forEach(u -> {
                if (u.getOrgUnitId() != null) {
                    orgIds.add(u.getOrgUnitId());
                }
            });
            if (!orgIds.isEmpty()) {
                for (SysOrgUnit org : orgMapper.selectBatchIds(orgIds)) {
                    orgNames.put(org.getId(), org.getName());
                }
            }
        }
        List<RosterItem> items = records.stream().map(r -> {
            SysUser student = students.get(r.getStudentId());
            return new RosterItem(r.getId(), r.getStudentId(),
                    student == null ? null : student.getUsername(),
                    student == null ? null : student.getDisplayName(),
                    student == null ? null : student.getOrgUnitId(),
                    student == null ? null : orgNames.get(student.getOrgUnitId()),
                    r.getSelectedAt());
        }).toList();
        return new PageResult<>(items, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /** 是否已选该课程（请假、知识库 COURSE 可见范围复用）。 */
    public boolean isEnrolled(long studentId, long courseId) {
        return selectionMapper.selectActive(courseId, studentId) != null;
    }

    /**
     * 取本人的有效选课目标信息，供 AI 工具的“退课”预览展示课程名。
     * 只做归属与状态校验，不改变任何数据。
     */
    public SelectionTarget requireOwnSelection(AuthenticatedUser user, long selectionId) {
        CourseSelection selection = selectionMapper.selectById(selectionId);
        if (selection == null || !Objects.equals(selection.getStudentId(), user.userId())
                || selection.getStatus() != SelectionStatus.SELECTED) {
            throw BusinessException.notFound("选课记录");
        }
        Course course = courseMapper.selectById(selection.getCourseId());
        return new SelectionTarget(selection.getId(), selection.getCourseId(),
                course == null ? null : course.getCode(), course == null ? null : course.getName());
    }

    /** 选课目标（预览用，只含展示所需字段）。 */
    public record SelectionTarget(long id, long courseId, String courseCode, String courseName) {
    }

    public List<Long> selectedCourseIds(long studentId) {
        return selectionMapper.selectActiveCourseIds(studentId);
    }

    // ------------------------------------------------------------------ 内部

    /** 与本学期其它已选课程的排课冲突；没有冲突返回 null。 */
    private CourseSchedule findConflict(long studentId, Course course) {
        List<CourseSchedule> target = scheduleMapper.selectByCourse(course.getId());
        if (target.isEmpty()) {
            return null;
        }
        Set<Long> selected = new HashSet<>(selectionMapper.selectActiveCourseIds(studentId));
        selected.remove(course.getId());
        if (selected.isEmpty()) {
            return null;
        }
        Collection<Long> sameSemester = courseMapper.selectBatchIds(selected).stream()
                .filter(c -> Objects.equals(c.getSemesterId(), course.getSemesterId()))
                .map(Course::getId)
                .toList();
        if (sameSemester.isEmpty()) {
            return null;
        }
        List<CourseSchedule> mine = scheduleMapper.selectByCourses(sameSemester);
        for (CourseSchedule candidate : target) {
            for (CourseSchedule other : mine) {
                if (ScheduleConflict.overlaps(candidate, other)) {
                    return other;
                }
            }
        }
        return null;
    }

    private SelectionView view(CourseSelection selection, Course course, Semester semester, boolean withTeachers) {
        Map<Long, List<String>> teacherNames = withTeachers
                ? courseService.teacherNamesOf(List.of(course.getId()))
                : Map.of();
        return view(selection, course, semester == null ? null : semester.getName(),
                teacherNames.getOrDefault(course.getId(), List.of()));
    }

    private SelectionView view(CourseSelection selection, Course course, String semesterName,
                               List<String> teacherNames) {
        return new SelectionView(selection.getId(), course.getId(), course.getCode(), course.getName(),
                course.getCredit(), course.getSemesterId(), semesterName, selection.getStatus(),
                teacherNames, selection.getSelectedAt(), selection.getDroppedAt());
    }

    private Map<Long, String> semesterNames(Collection<Long> ids) {
        Set<Long> clean = CourseService.clean(ids);
        Map<Long, String> result = new HashMap<>();
        if (!clean.isEmpty()) {
            semesterMapper.selectBatchIds(clean).forEach(s -> result.put(s.getId(), s.getName()));
        }
        return result;
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("COURSE_SELECTION", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
