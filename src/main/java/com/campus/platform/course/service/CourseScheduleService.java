package com.campus.platform.course.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.dto.MyScheduleItem;
import com.campus.platform.course.dto.ScheduleRequest;
import com.campus.platform.course.dto.ScheduleView;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.CourseSchedule;
import com.campus.platform.course.entity.CourseStatus;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.entity.WeekParity;
import com.campus.platform.course.mapper.CourseMapper;
import com.campus.platform.course.mapper.CourseScheduleMapper;
import com.campus.platform.course.mapper.CourseSelectionMapper;
import com.campus.platform.course.mapper.CourseTeacherMapper;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 排课与课表。
 * <p>
 * 排课冲突分三层校验：
 * <ol>
 *   <li>参数自身合法（节次、周次区间有序）；</li>
 *   <li>与本课程已有排课不重叠（同一课程也不能同时上两处）；</li>
 *   <li>与本课程全部授课教师在本学期的其它课程不重叠（教师不能分身）。</li>
 * </ol>
 * “我的课表”对学生返回已选课程、对教师返回任教课程，两者都有时合并去重。
 */
@Service
public class CourseScheduleService {

    static final String MODULE = "course";

    private final CourseScheduleMapper scheduleMapper;
    private final CourseMapper courseMapper;
    private final SemesterMapper semesterMapper;
    private final CourseTeacherMapper teacherMapper;
    private final CourseSelectionMapper selectionMapper;
    private final CourseService courseService;
    private final SemesterService semesterService;
    private final CourseAccessService accessService;
    private final AuditService auditService;
    private final Clock clock;

    public CourseScheduleService(CourseScheduleMapper scheduleMapper, CourseMapper courseMapper,
                                 SemesterMapper semesterMapper, CourseTeacherMapper teacherMapper,
                                 CourseSelectionMapper selectionMapper, CourseService courseService,
                                 SemesterService semesterService, CourseAccessService accessService,
                                 AuditService auditService, Clock clock) {
        this.scheduleMapper = scheduleMapper;
        this.courseMapper = courseMapper;
        this.semesterMapper = semesterMapper;
        this.teacherMapper = teacherMapper;
        this.selectionMapper = selectionMapper;
        this.courseService = courseService;
        this.semesterService = semesterService;
        this.accessService = accessService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public List<ScheduleView> list(AuthenticatedUser user, long courseId) {
        Permissions.require(user, Permissions.COURSE_READ);
        courseService.requireReadable(user, courseId);
        return scheduleMapper.selectByCourse(courseId).stream().map(ScheduleView::of).toList();
    }

    @Transactional
    public List<ScheduleView> add(AuthenticatedUser user, long courseId, ScheduleRequest req) {
        Course course = courseService.requireManageable(user, courseId);
        semesterService.requireNotClosed(course.getSemesterId());

        CourseSchedule candidate = candidate(courseId, req);
        requireNoConflict(course, candidate, null);
        scheduleMapper.insert(candidate);

        audit("COURSE_SCHEDULE_CREATE", courseId, "scheduleId", candidate.getId(),
                "slot", slotText(candidate));
        return scheduleMapper.selectByCourse(courseId).stream().map(ScheduleView::of).toList();
    }

    @Transactional
    public List<ScheduleView> update(AuthenticatedUser user, long courseId, long scheduleId, ScheduleRequest req) {
        CourseSchedule existing = requireSchedule(courseId, scheduleId);
        Course course = courseService.requireManageable(user, courseId);
        semesterService.requireNotClosed(course.getSemesterId());

        CourseSchedule candidate = candidate(existing.getCourseId(), req);
        requireNoConflict(course, candidate, scheduleId);

        int updated = scheduleMapper.update(null, new LambdaUpdateWrapper<CourseSchedule>()
                .eq(CourseSchedule::getId, scheduleId)
                .set(CourseSchedule::getDayOfWeek, candidate.getDayOfWeek())
                .set(CourseSchedule::getStartPeriod, candidate.getStartPeriod())
                .set(CourseSchedule::getEndPeriod, candidate.getEndPeriod())
                .set(CourseSchedule::getStartWeek, candidate.getStartWeek())
                .set(CourseSchedule::getEndWeek, candidate.getEndWeek())
                .set(CourseSchedule::getWeekParity, candidate.getWeekParity())
                .set(CourseSchedule::getClassroom, candidate.getClassroom())
                .set(CourseSchedule::getUpdatedBy, user.userId())
                .set(CourseSchedule::getUpdatedAt, LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.conflict("排课已被其他人修改，请刷新后重试");
        }
        audit("COURSE_SCHEDULE_UPDATE", existing.getCourseId(), "scheduleId", scheduleId,
                "slot", slotText(candidate));
        return scheduleMapper.selectByCourse(existing.getCourseId()).stream().map(ScheduleView::of).toList();
    }

    @Transactional
    public void delete(AuthenticatedUser user, long courseId, long scheduleId) {
        CourseSchedule existing = requireSchedule(courseId, scheduleId);
        Course course = courseService.requireManageable(user, courseId);
        semesterService.requireNotClosed(course.getSemesterId());
        scheduleMapper.deleteById(scheduleId);
        audit("COURSE_SCHEDULE_DELETE", existing.getCourseId(), "scheduleId", scheduleId,
                "slot", slotText(existing));
    }

    /**
     * 我的课表：学生看已选课程、教师看任教课程；两者都有时合并。
     * 已停开课程不出现在课表中；{@code semesterId} 为空时返回所有学期。
     */
    public List<MyScheduleItem> mySchedule(AuthenticatedUser user, Long semesterId) {
        Permissions.require(user, Permissions.COURSE_READ);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Set<Long> courseIds = new LinkedHashSet<>();
        courseIds.addAll(teacherMapper.selectCourseIdsByTeachers(List.of(user.userId())));
        courseIds.addAll(selectionMapper.selectActiveCourseIds(user.userId()));
        if (courseIds.isEmpty()) {
            return List.of();
        }

        List<Course> courses = courseMapper.selectBatchIds(courseIds).stream()
                .filter(c -> c.getStatus() != CourseStatus.CANCELLED)
                .filter(c -> semesterId == null || Objects.equals(c.getSemesterId(), semesterId))
                .toList();
        if (courses.isEmpty()) {
            return List.of();
        }
        Map<Long, Course> byId = new HashMap<>();
        courses.forEach(c -> byId.put(c.getId(), c));
        Map<Long, String> semesterNames = new HashMap<>();
        semesterMapper.selectBatchIds(courses.stream().map(Course::getSemesterId).collect(java.util.stream.Collectors.toSet()))
                .forEach(s -> semesterNames.put(s.getId(), s.getName()));
        Map<Long, List<String>> teacherNames = courseService.teacherNamesOf(byId.keySet());

        List<MyScheduleItem> items = new ArrayList<>();
        for (CourseSchedule s : scheduleMapper.selectByCourses(byId.keySet())) {
            Course course = byId.get(s.getCourseId());
            if (course == null) {
                continue;
            }
            items.add(new MyScheduleItem(course.getSemesterId(), semesterNames.get(course.getSemesterId()),
                    course.getId(), course.getCode(), course.getName(),
                    teacherNames.getOrDefault(course.getId(), List.of()), s.getId(), s.getDayOfWeek(),
                    s.getStartPeriod(), s.getEndPeriod(), s.getStartWeek(), s.getEndWeek(),
                    s.getWeekParity() == null ? WeekParity.ALL : s.getWeekParity(), s.getClassroom()));
        }
        items.sort(Comparator.comparing(MyScheduleItem::dayOfWeek).thenComparing(MyScheduleItem::startPeriod));
        return items;
    }

    // ------------------------------------------------------------------ 内部

    private CourseSchedule requireSchedule(long courseId, long scheduleId) {
        CourseSchedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null || !Objects.equals(schedule.getCourseId(), courseId)) {
            throw BusinessException.notFound("排课");
        }
        return schedule;
    }

    private CourseSchedule candidate(long courseId, ScheduleRequest req) {
        if (req.startPeriod() > req.endPeriod()) {
            throw new BusinessException(ErrorCode.COURSE_SCHEDULE_INVALID, "开始节次不能晚于结束节次");
        }
        if (req.startWeek() > req.endWeek()) {
            throw new BusinessException(ErrorCode.COURSE_SCHEDULE_INVALID, "开始周不能晚于结束周");
        }
        CourseSchedule candidate = new CourseSchedule();
        candidate.setCourseId(courseId);
        candidate.setDayOfWeek(req.dayOfWeek());
        candidate.setStartPeriod(req.startPeriod());
        candidate.setEndPeriod(req.endPeriod());
        candidate.setStartWeek(req.startWeek());
        candidate.setEndWeek(req.endWeek());
        candidate.setWeekParity(req.weekParity() == null ? WeekParity.ALL : req.weekParity());
        candidate.setClassroom(CourseService.blankToNull(req.classroom()));
        return candidate;
    }

    private void requireNoConflict(Course course, CourseSchedule candidate, Long excludeScheduleId) {
        List<CourseSchedule> sameCourse = scheduleMapper.selectByCourse(course.getId()).stream()
                .filter(s -> excludeScheduleId == null || !Objects.equals(s.getId(), excludeScheduleId))
                .toList();
        if (ScheduleConflict.firstConflict(candidate, sameCourse).isPresent()) {
            throw new BusinessException(ErrorCode.COURSE_SCHEDULE_CONFLICT, "与本课程已有排课时间冲突");
        }

        List<Long> teacherIds = accessService.teacherIdsOf(course.getId());
        if (teacherIds.isEmpty()) {
            return;
        }
        List<Long> otherCourses = teacherMapper.selectCourseIdsByTeachers(teacherIds).stream()
                .filter(id -> !Objects.equals(id, course.getId()))
                .toList();
        if (otherCourses.isEmpty()) {
            return;
        }
        Collection<Long> sameSemester = otherCourses.stream()
                .map(courseMapper::selectById)
                .filter(Objects::nonNull)
                .filter(c -> Objects.equals(c.getSemesterId(), course.getSemesterId()))
                .map(Course::getId)
                .toList();
        if (sameSemester.isEmpty()) {
            return;
        }
        CourseSchedule conflict = ScheduleConflict.firstConflict(candidate,
                scheduleMapper.selectByCourses(sameSemester)).orElse(null);
        if (conflict != null) {
            Course other = courseMapper.selectById(conflict.getCourseId());
            throw new BusinessException(ErrorCode.COURSE_SCHEDULE_CONFLICT,
                    "授课教师在该时间段已有课程：" + (other == null ? conflict.getCourseId() : other.getName()));
        }
    }

    private static String slotText(CourseSchedule s) {
        return "周" + s.getDayOfWeek() + " 第" + s.getStartPeriod() + "-" + s.getEndPeriod() + "节 "
                + s.getStartWeek() + "-" + s.getEndWeek() + "周 " + s.getWeekParity();
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("COURSE", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
