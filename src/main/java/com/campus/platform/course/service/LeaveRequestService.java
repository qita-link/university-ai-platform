package com.campus.platform.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.dto.LeaveQuery;
import com.campus.platform.course.dto.LeaveSubmitRequest;
import com.campus.platform.course.dto.LeaveView;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.CourseSchedule;
import com.campus.platform.course.entity.CourseStatus;
import com.campus.platform.course.entity.LeaveRequest;
import com.campus.platform.course.entity.LeaveStatus;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.mapper.CourseMapper;
import com.campus.platform.course.mapper.CourseScheduleMapper;
import com.campus.platform.course.mapper.LeaveRequestMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 请假申请与审批。
 * <p>
 * 状态机：{@code PENDING → APPROVED/REJECTED}（任课教师或管理员）、
 * {@code PENDING → CANCELLED}（申请人本人）。所有状态变更都使用
 * {@code WHERE status = 'PENDING'} 条件更新，并发审批时只有一个请求成功，其余返回 409。
 * 教师只能审批自己任教（主讲或助教）的课程；管理员凭 {@code leave:manage_all} 可审批全部。
 */
@Service
public class LeaveRequestService {

    static final String MODULE = "leave";

    private final LeaveRequestMapper leaveMapper;
    private final CourseMapper courseMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final CourseService courseService;
    private final CourseSelectionService selectionService;
    private final CourseAccessService accessService;
    private final SemesterService semesterService;
    private final SysUserMapper userMapper;
    private final AuditService auditService;
    private final Clock clock;

    public LeaveRequestService(LeaveRequestMapper leaveMapper, CourseMapper courseMapper,
                               CourseScheduleMapper scheduleMapper, CourseService courseService,
                               CourseSelectionService selectionService, CourseAccessService accessService,
                               SemesterService semesterService, SysUserMapper userMapper,
                               AuditService auditService, Clock clock) {
        this.leaveMapper = leaveMapper;
        this.courseMapper = courseMapper;
        this.scheduleMapper = scheduleMapper;
        this.courseService = courseService;
        this.selectionService = selectionService;
        this.accessService = accessService;
        this.semesterService = semesterService;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public LeaveView submit(AuthenticatedUser user, LeaveSubmitRequest req) {
        Permissions.require(user, Permissions.LEAVE_SUBMIT);
        Course course = courseService.requireCourse(req.courseId());
        if (course.getStatus() == CourseStatus.CANCELLED) {
            throw BusinessException.notFound("课程");
        }
        if (!selectionService.isEnrolled(user.userId(), course.getId())) {
            throw new BusinessException(ErrorCode.SELECTION_NOT_ENROLLED, "只有已选课程才能请假");
        }
        if (!req.startAt().isBefore(req.endAt())) {
            throw new BusinessException(ErrorCode.LEAVE_TIME_INVALID, "请假开始时间必须早于结束时间");
        }
        Semester semester = semesterService.require(course.getSemesterId());
        if (!semester.covers(req.startAt().toLocalDate()) || !semester.covers(req.endAt().toLocalDate())) {
            throw new BusinessException(ErrorCode.LEAVE_TIME_INVALID,
                    "请假时间必须在学期范围内（" + semester.getStartDate() + " ~ " + semester.getEndDate() + "）");
        }
        if (req.scheduleId() != null) {
            CourseSchedule schedule = scheduleMapper.selectById(req.scheduleId());
            if (schedule == null || !Objects.equals(schedule.getCourseId(), course.getId())) {
                throw new BusinessException(ErrorCode.LEAVE_TIME_INVALID, "所选排课不属于该课程");
            }
        }

        LeaveRequest leave = new LeaveRequest();
        leave.setStudentId(user.userId());
        leave.setCourseId(course.getId());
        leave.setScheduleId(req.scheduleId());
        leave.setStartAt(req.startAt());
        leave.setEndAt(req.endAt());
        leave.setReason(req.reason().trim());
        leave.setStatus(LeaveStatus.PENDING);
        leave.setVersion(0);
        leaveMapper.insert(leave);

        audit("LEAVE_SUBMIT", leave.getId(), "courseId", course.getId(), "courseCode", course.getCode(),
                "startAt", req.startAt(), "endAt", req.endAt());
        return view(leave, user, Map.of(course.getId(), course), Map.of());
    }

    /** 本人请假记录。 */
    public PageResult<LeaveView> myRequests(AuthenticatedUser user, LeaveQuery q) {
        Permissions.require(user, Permissions.LEAVE_SUBMIT);
        LambdaQueryWrapper<LeaveRequest> w = new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getStudentId, user.userId())
                .eq(q.getStatus() != null, LeaveRequest::getStatus, q.getStatus());
        applyFilters(w, q);
        return page(user, q, w);
    }

    /**
     * 待我审批的申请：管理员（{@code leave:manage_all}）看全部，教师只看自己任教的课程。
     * 默认只看 PENDING，可显式传 status 查看历史。
     */
    public PageResult<LeaveView> toReview(AuthenticatedUser user, LeaveQuery q) {
        LambdaQueryWrapper<LeaveRequest> w = new LambdaQueryWrapper<>();
        if (!user.hasPermission(Permissions.LEAVE_MANAGE_ALL)) {
            Permissions.require(user, Permissions.LEAVE_REVIEW);
            List<Long> courseIds = accessService.teachingCourseIds(user.userId());
            if (courseIds.isEmpty()) {
                return PageResult.empty(q.getPage(), q.getSize());
            }
            w.in(LeaveRequest::getCourseId, courseIds);
        }
        if (q.getStatus() == null) {
            w.eq(LeaveRequest::getStatus, LeaveStatus.PENDING);
        } else {
            w.eq(LeaveRequest::getStatus, q.getStatus());
        }
        applyFilters(w, q);
        return page(user, q, w);
    }

    public LeaveView get(AuthenticatedUser user, long id) {
        LeaveRequest leave = requireLeave(id);
        Course course = courseService.requireCourse(leave.getCourseId());
        boolean applicant = Objects.equals(leave.getStudentId(), user.userId());
        if (!applicant && !accessService.canReviewLeave(user, leave.getCourseId())) {
            throw BusinessException.notFound("请假申请");
        }
        return view(leave, user, Map.of(course.getId(), course), Map.of());
    }

    @Transactional
    public LeaveView approve(AuthenticatedUser user, long id, String comment) {
        return decide(user, id, LeaveStatus.APPROVED, comment);
    }

    @Transactional
    public LeaveView reject(AuthenticatedUser user, long id, String comment) {
        return decide(user, id, LeaveStatus.REJECTED, comment);
    }

    /** 申请人撤销：只能撤销本人且仍为 PENDING 的申请。 */
    @Transactional
    public LeaveView cancel(AuthenticatedUser user, long id, String comment) {
        Permissions.require(user, Permissions.LEAVE_SUBMIT);
        LeaveRequest leave = requireLeave(id);
        if (!Objects.equals(leave.getStudentId(), user.userId())) {
            throw BusinessException.forbidden("只能撤销本人的请假申请");
        }
        String text = comment == null || comment.isBlank() ? "申请人撤销" : comment.trim();
        if (leaveMapper.cancel(id, user.userId(), text, LocalDateTime.now(clock)) != 1) {
            throw BusinessException.conflict("请假申请已被处理，无法撤销");
        }
        audit("LEAVE_CANCEL", id, "courseId", leave.getCourseId());
        return get(user, id);
    }

    private LeaveView decide(AuthenticatedUser user, long id, LeaveStatus target, String comment) {
        LeaveRequest leave = requireLeave(id);
        if (!accessService.canReviewLeave(user, leave.getCourseId())) {
            throw BusinessException.forbidden("只能审批本人任教课程的请假申请");
        }
        if (Objects.equals(leave.getStudentId(), user.userId())) {
            throw BusinessException.forbidden("不能审批自己的请假申请");
        }
        String text = comment == null || comment.isBlank() ? null : comment.trim();
        if (leaveMapper.decide(id, target, user.userId(), text, LocalDateTime.now(clock)) != 1) {
            throw BusinessException.conflict("请假申请已被处理，请刷新后重试");
        }
        audit(target == LeaveStatus.APPROVED ? "LEAVE_APPROVE" : "LEAVE_REJECT", id,
                "courseId", leave.getCourseId(), "comment", text);
        return get(user, id);
    }

    /** 只追加课程/学期过滤；状态条件由调用方决定（myRequests 不限状态，toReview 默认 PENDING）。 */
    private void applyFilters(LambdaQueryWrapper<LeaveRequest> w, LeaveQuery q) {
        if (q.getCourseId() != null) {
            w.eq(LeaveRequest::getCourseId, q.getCourseId());
        }
        if (q.getSemesterId() != null) {
            List<Long> courseIds = courseMapper.selectList(new LambdaQueryWrapper<Course>()
                            .select(Course::getId)
                            .eq(Course::getSemesterId, q.getSemesterId()))
                    .stream().map(Course::getId).toList();
            if (courseIds.isEmpty()) {
                w.apply("1 = 0");
            } else {
                w.in(LeaveRequest::getCourseId, courseIds);
            }
        }
    }

    private PageResult<LeaveView> page(AuthenticatedUser user, LeaveQuery q, LambdaQueryWrapper<LeaveRequest> w) {
        w.orderByDesc(LeaveRequest::getId);
        Page<LeaveRequest> pageRequest = q.toPage();
        IPage<LeaveRequest> page = leaveMapper.selectPage(pageRequest, w);
        List<LeaveRequest> records = page.getRecords();

        Map<Long, Course> courses = new HashMap<>();
        Map<Long, SysUser> users = new HashMap<>();
        if (!records.isEmpty()) {
            Set<Long> courseIds = new HashSet<>();
            Set<Long> userIds = new HashSet<>();
            records.forEach(r -> {
                courseIds.add(r.getCourseId());
                userIds.add(r.getStudentId());
                if (r.getReviewerId() != null) {
                    userIds.add(r.getReviewerId());
                }
            });
            courseMapper.selectBatchIds(courseIds).forEach(c -> courses.put(c.getId(), c));
            userMapper.selectBatchIds(userIds).forEach(u -> users.put(u.getId(), u));
        }
        List<LeaveView> items = new ArrayList<>();
        for (LeaveRequest leave : records) {
            items.add(view(leave, user, courses, users));
        }
        return new PageResult<>(items, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    private LeaveRequest requireLeave(long id) {
        LeaveRequest leave = leaveMapper.selectById(id);
        if (leave == null) {
            throw BusinessException.notFound("请假申请");
        }
        return leave;
    }

    private LeaveView view(LeaveRequest leave, AuthenticatedUser viewer, Map<Long, Course> courses,
                           Map<Long, SysUser> users) {
        Course course = courses.get(leave.getCourseId());
        SysUser student = users.get(leave.getStudentId());
        if (student == null) {
            student = userMapper.selectById(leave.getStudentId());
        }
        SysUser reviewer = leave.getReviewerId() == null ? null : users.get(leave.getReviewerId());
        if (leave.getReviewerId() != null && reviewer == null) {
            reviewer = userMapper.selectById(leave.getReviewerId());
        }
        boolean canReview = leave.getStatus() == LeaveStatus.PENDING
                && accessService.canReviewLeave(viewer, leave.getCourseId())
                && !Objects.equals(leave.getStudentId(), viewer.userId());
        boolean canCancel = leave.getStatus() == LeaveStatus.PENDING
                && Objects.equals(leave.getStudentId(), viewer.userId());
        return new LeaveView(leave.getId(), leave.getStudentId(),
                student == null ? null : student.getUsername(),
                student == null ? null : student.getDisplayName(),
                leave.getCourseId(), course == null ? null : course.getCode(),
                course == null ? null : course.getName(), leave.getScheduleId(),
                leave.getStartAt(), leave.getEndAt(), leave.getReason(), leave.getStatus(),
                leave.getReviewerId(), reviewer == null ? null : reviewer.getDisplayName(),
                leave.getReviewComment(), leave.getReviewedAt(), canReview, canCancel,
                leave.getCreatedAt(), CourseService.value(leave.getVersion()));
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("LEAVE_REQUEST", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
