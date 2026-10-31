package com.campus.platform.course.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.dto.ClassCounselorView;
import com.campus.platform.course.dto.LeaveApprovalView;
import com.campus.platform.course.dto.LeaveQuery;
import com.campus.platform.course.dto.LeaveSubmitRequest;
import com.campus.platform.course.dto.LeaveView;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.CourseSchedule;
import com.campus.platform.course.entity.CourseStatus;
import com.campus.platform.course.entity.LeaveApproval;
import com.campus.platform.course.entity.LeaveApproverRole;
import com.campus.platform.course.entity.LeaveDecision;
import com.campus.platform.course.entity.LeaveRequest;
import com.campus.platform.course.entity.LeaveStatus;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.mapper.ClassCounselorMapper;
import com.campus.platform.course.mapper.CourseMapper;
import com.campus.platform.course.mapper.CourseScheduleMapper;
import com.campus.platform.course.mapper.LeaveApprovalMapper;
import com.campus.platform.course.mapper.LeaveRequestMapper;
import com.campus.platform.notification.entity.NotificationCategory;
import com.campus.platform.notification.entity.NotificationLevel;
import com.campus.platform.notification.service.NotificationService;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.security.Roles;
import com.campus.platform.setting.service.SettingService;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 请假申请、双级审批、销假与查询。
 * <p>
 * 审批流程由管理端设置决定（任课教师 / 班级导员，可配置顺序），提交时把顺序与级数快照到申请行上，
 * 之后调整设置不影响已提交的申请。状态机仍然是
 * {@code PENDING → APPROVED/REJECTED}（逐级审批）与 {@code PENDING → CANCELLED}（申请人撤销）；
 * 销假不改状态，只写 {@code revoked_at}，避免破坏「已通过」这一终态。
 * <p>
 * 并发控制：
 * <ul>
 *   <li>每一级审批用 {@code WHERE status='PENDING' AND current_stage=?} 条件更新，同一级只有一个审批人成功；</li>
 *   <li>{@code leave_approval} 的 (leave_id, stage) 唯一键兜底，防止重复留言；</li>
 *   <li>销假用 {@code AND revoked_at IS NULL} 保证只销一次。</li>
 * </ul>
 */
@Service
public class LeaveRequestService {

    static final String MODULE = "leave";

    private final LeaveRequestMapper leaveMapper;
    private final LeaveApprovalMapper approvalMapper;
    private final ClassCounselorMapper counselorMapper;
    private final CourseMapper courseMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final CourseService courseService;
    private final CourseSelectionService selectionService;
    private final CourseAccessService accessService;
    private final SemesterService semesterService;
    private final SysUserMapper userMapper;
    private final SysOrgUnitMapper orgMapper;
    private final SettingService settingService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    public LeaveRequestService(LeaveRequestMapper leaveMapper, LeaveApprovalMapper approvalMapper,
                               ClassCounselorMapper counselorMapper, CourseMapper courseMapper,
                               CourseScheduleMapper scheduleMapper, CourseService courseService,
                               CourseSelectionService selectionService, CourseAccessService accessService,
                               SemesterService semesterService, SysUserMapper userMapper,
                               SysOrgUnitMapper orgMapper, SettingService settingService,
                               NotificationService notificationService, AuditService auditService, Clock clock) {
        this.leaveMapper = leaveMapper;
        this.approvalMapper = approvalMapper;
        this.counselorMapper = counselorMapper;
        this.courseMapper = courseMapper;
        this.scheduleMapper = scheduleMapper;
        this.courseService = courseService;
        this.selectionService = selectionService;
        this.accessService = accessService;
        this.semesterService = semesterService;
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.settingService = settingService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /* ---------------- 提交 ---------------- */

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

        SysUser student = userMapper.selectById(user.userId());
        SysOrgUnit orgUnit = student == null || student.getOrgUnitId() == null
                ? null : orgMapper.selectById(student.getOrgUnitId());
        Long classId = orgUnit != null && orgUnit.getType() == OrgType.CLASS ? orgUnit.getId() : null;
        Long counselorId = null;
        if (classId != null) {
            List<Long> counselors = counselorMapper.selectCounselorIds(classId);
            counselorId = counselors.isEmpty() ? null : counselors.get(0);
        }

        List<String> stages = resolveStages(counselorId != null);
        LeaveRequest leave = new LeaveRequest();
        leave.setStudentId(user.userId());
        leave.setClassId(classId);
        leave.setCounselorId(counselorId);
        leave.setCourseId(course.getId());
        leave.setScheduleId(req.scheduleId());
        leave.setStartAt(req.startAt());
        leave.setEndAt(req.endAt());
        leave.setReason(req.reason().trim());
        leave.setStatus(LeaveStatus.PENDING);
        leave.setApprovalOrder(String.join(",", stages));
        leave.setTotalStages(stages.size());
        leave.setCurrentStage(1);
        leave.setVersion(0);
        leaveMapper.insert(leave);

        audit("LEAVE_SUBMIT", leave.getId(), "courseId", course.getId(), "courseCode", course.getCode(),
                "startAt", req.startAt(), "endAt", req.endAt(), "stages", leave.getApprovalOrder());
        notifyApprovers(leave, course, 1);
        return get(user, leave.getId());
    }

    /** 按平台设置解析实际生效的审批级别；班级未配导员时自动跳过导员环节。 */
    private List<String> resolveStages(boolean counselorAvailable) {
        SettingService.LeaveFlow flow = settingService.leaveFlow();
        List<String> stages = new ArrayList<>();
        for (String role : flow.stages()) {
            if (SettingService.ROLE_COUNSELOR.equals(role) && !counselorAvailable) {
                continue;
            }
            stages.add(role);
        }
        if (stages.isEmpty()) {
            stages.add(SettingService.ROLE_TEACHER);
        }
        return stages;
    }

    /** 通知某一级的审批人：教师取任课教师，导员取班级导员。 */
    private void notifyApprovers(LeaveRequest leave, Course course, int stage) {
        String role = stageRole(leave, stage);
        List<Long> recipients = SettingService.ROLE_COUNSELOR.equals(role)
                ? (leave.getClassId() == null ? List.of() : counselorMapper.selectCounselorIds(leave.getClassId()))
                : accessService.teacherIdsOf(leave.getCourseId());
        if (recipients.isEmpty()) {
            return;
        }
        SysUser student = userMapper.selectById(leave.getStudentId());
        notificationService.notifyAll(recipients, NotificationCategory.LEAVE, NotificationLevel.WARNING,
                "有新的请假待审批",
                (student == null ? "学生" : student.getDisplayName()) + " 在《" + course.getName() + "》请假："
                        + leave.getStartAt() + " ~ " + leave.getEndAt(),
                "/leave?tab=review", "LEAVE_REQUEST", leave.getId());
    }

    private String stageRole(LeaveRequest leave, int stage) {
        List<String> roles = stageRoles(leave);
        int index = Math.max(0, Math.min(stage - 1, roles.size() - 1));
        return roles.get(index);
    }

    /* ---------------- 查询 ---------------- */

    public PageResult<LeaveView> myRequests(AuthenticatedUser user, LeaveQuery q) {
        Permissions.require(user, Permissions.LEAVE_SUBMIT);
        LambdaQueryWrapper<LeaveRequest> w = new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getStudentId, user.userId())
                .eq(q.getStatus() != null, LeaveRequest::getStatus, q.getStatus());
        applyFilters(w, q);
        return page(user, q, w);
    }

    /**
     * 待我审批：管理员（leave:manage_all）看全部；教师看自己任教课程与所带班级中「当前环节轮到我」的申请。
     * 默认只看 PENDING，可显式传 status 查看历史。
     */
    public PageResult<LeaveView> toReview(AuthenticatedUser user, LeaveQuery q) {
        Permissions.requireAny(user, Permissions.LEAVE_REVIEW, Permissions.LEAVE_MANAGE_ALL);
        LambdaQueryWrapper<LeaveRequest> w = new LambdaQueryWrapper<>();
        if (!user.hasPermission(Permissions.LEAVE_MANAGE_ALL)) {
            List<Long> courseIds = accessService.teachingCourseIds(user.userId());
            List<Long> classIds = counselorMapper.selectClassIds(user.userId());
            if (courseIds.isEmpty() && classIds.isEmpty()) {
                return PageResult.empty(q.getPage(), q.getSize());
            }
            w.and(x -> {
                if (!courseIds.isEmpty()) {
                    x.in(LeaveRequest::getCourseId, courseIds);
                }
                if (!classIds.isEmpty()) {
                    if (!courseIds.isEmpty()) {
                        x.or();
                    }
                    x.in(LeaveRequest::getClassId, classIds);
                }
            });
        }
        w.eq(LeaveRequest::getStatus, q.getStatus() == null ? LeaveStatus.PENDING : q.getStatus());
        applyFilters(w, q);
        w.orderByAsc(LeaveRequest::getId);

        List<LeaveRequest> candidates = leaveMapper.selectList(w);
        // 待审批只列「轮到我」的；查历史（显式传状态）则按可见范围列出
        List<LeaveRequest> actionable = candidates.stream()
                .filter(l -> l.getStatus() == LeaveStatus.PENDING ? canActNow(user, l) : canView(user, l))
                .toList();
        int page = q.getPage();
        int size = q.getSize();
        int from = Math.min((page - 1) * size, actionable.size());
        int to = Math.min(from + size, actionable.size());
        List<LeaveRequest> slice = actionable.subList(from, to);
        List<LeaveView> items = views(slice, user);
        long pages = size == 0 ? 0 : (actionable.size() + size - 1) / size;
        return new PageResult<>(items, actionable.size(), page, size, pages);
    }

    /**
     * 全部请假查询：学生会（STUDENT_UNION）与管理员看全部，教师看自己任教课程与所带班级。
     * 只读，不提供审批能力。
     */
    public PageResult<LeaveView> all(AuthenticatedUser user, LeaveQuery q) {
        Permissions.require(user, Permissions.LEAVE_READ_ALL);
        LambdaQueryWrapper<LeaveRequest> w = new LambdaQueryWrapper<>();
        boolean unrestricted = user.hasPermission(Permissions.LEAVE_MANAGE_ALL)
                || user.hasRole(Roles.STUDENT_UNION);
        if (!unrestricted) {
            List<Long> courseIds = accessService.teachingCourseIds(user.userId());
            List<Long> classIds = counselorMapper.selectClassIds(user.userId());
            if (courseIds.isEmpty() && classIds.isEmpty()) {
                return PageResult.empty(q.getPage(), q.getSize());
            }
            w.and(x -> {
                if (!courseIds.isEmpty()) {
                    x.in(LeaveRequest::getCourseId, courseIds);
                }
                if (!classIds.isEmpty()) {
                    if (!courseIds.isEmpty()) {
                        x.or();
                    }
                    x.in(LeaveRequest::getClassId, classIds);
                }
            });
        }
        w.eq(q.getStatus() != null, LeaveRequest::getStatus, q.getStatus());
        applyFilters(w, q);
        return page(user, q, w);
    }

    public LeaveView get(AuthenticatedUser user, long id) {
        LeaveRequest leave = requireLeave(id);
        if (!canView(user, leave)) {
            throw BusinessException.notFound("请假申请");
        }
        return views(List.of(leave), user).get(0);
    }

    /* ---------------- 审批与销假 ---------------- */

    @Transactional
    public LeaveView approve(AuthenticatedUser user, long id, String comment) {
        return decide(user, id, LeaveDecision.APPROVED, comment);
    }

    @Transactional
    public LeaveView reject(AuthenticatedUser user, long id, String comment) {
        return decide(user, id, LeaveDecision.REJECTED, comment);
    }

    private LeaveView decide(AuthenticatedUser user, long id, LeaveDecision decision, String comment) {
        Permissions.requireAny(user, Permissions.LEAVE_REVIEW, Permissions.LEAVE_MANAGE_ALL);
        LeaveRequest leave = requireLeave(id);
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw BusinessException.conflict("请假申请已被处理，请刷新后重试");
        }
        if (Objects.equals(leave.getStudentId(), user.userId())) {
            throw BusinessException.forbidden("不能审批自己的请假申请");
        }
        if (!canActNow(user, leave)) {
            throw BusinessException.forbidden("当前环节应由" + roleLabel(currentRole(leave)) + "审批");
        }
        String text = comment == null || comment.isBlank() ? null : comment.trim();
        int stage = leave.getCurrentStage() == null ? 1 : leave.getCurrentStage();
        int total = leave.getTotalStages() == null ? 1 : leave.getTotalStages();

        LeaveApproval approval = new LeaveApproval();
        approval.setLeaveId(leave.getId());
        approval.setStage(stage);
        approval.setApproverRole(roleOf(currentRole(leave)));
        approval.setApproverId(user.userId());
        approval.setDecision(decision);
        approval.setComment(text);
        approval.setDecidedAt(LocalDateTime.now(clock));
        try {
            approvalMapper.insert(approval);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("该环节已有审批结论，请刷新后重试");
        }

        int updated;
        if (decision == LeaveDecision.REJECTED || stage >= total) {
            updated = leaveMapper.finishStage(leave.getId(), stage,
                    decision == LeaveDecision.REJECTED ? LeaveStatus.REJECTED : LeaveStatus.APPROVED,
                    user.userId(), text, LocalDateTime.now(clock));
        } else {
            updated = leaveMapper.advanceStage(leave.getId(), stage, stage + 1, user.userId(), text,
                    LocalDateTime.now(clock));
        }
        if (updated != 1) {
            throw BusinessException.conflict("请假申请已被其他审批人处理，请刷新后重试");
        }
        audit(decision == LeaveDecision.APPROVED ? "LEAVE_APPROVE" : "LEAVE_REJECT",
                leave.getId(), "stage", stage, "role", currentRole(leave), "comment", text);
        Course course = courseMapper.selectById(leave.getCourseId());
        String courseName = course == null ? "课程" : course.getName();
        if (decision == LeaveDecision.REJECTED) {
            notificationService.notify(leave.getStudentId(), NotificationCategory.LEAVE,
                    NotificationLevel.WARNING, "请假被驳回",
                    "《" + courseName + "》" + leave.getStartAt() + " ~ " + leave.getEndAt()
                            + " 的请假被驳回" + (text == null ? "" : "：" + text),
                    "/leave", "LEAVE_REQUEST", leave.getId());
        } else if (stage >= total) {
            notificationService.notify(leave.getStudentId(), NotificationCategory.LEAVE,
                    NotificationLevel.SUCCESS, "请假已通过",
                    "《" + courseName + "》的请假已全部审批通过，返校后记得销假。",
                    "/leave", "LEAVE_REQUEST", leave.getId());
        } else {
            LeaveRequest fresh = leaveMapper.selectById(leave.getId());
            if (fresh != null && course != null) {
                notifyApprovers(fresh, course, stage + 1);
            }
            notificationService.notify(leave.getStudentId(), NotificationCategory.LEAVE,
                    NotificationLevel.INFO, "请假已通过一级",
                    "《" + courseName + "》的请假已通过" + roleLabel(currentRole(leave)) + "审批，正在等待下一级。",
                    "/leave", "LEAVE_REQUEST", leave.getId());
        }
        return get(user, id);
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

    /** 销假：请假通过后、返校时由本人确认，写入销假时间与说明。 */
    @Transactional
    public LeaveView revoke(AuthenticatedUser user, long id, String note) {
        Permissions.require(user, Permissions.LEAVE_SUBMIT);
        LeaveRequest leave = requireLeave(id);
        if (!Objects.equals(leave.getStudentId(), user.userId())) {
            throw BusinessException.forbidden("只能为本人请假销假");
        }
        if (leave.getStatus() != LeaveStatus.APPROVED) {
            throw BusinessException.rule("只有已通过的请假才能销假");
        }
        String text = note == null || note.isBlank() ? "已返校销假" : note.trim();
        if (leaveMapper.revoke(id, user.userId(), text, LocalDateTime.now(clock)) != 1) {
            throw BusinessException.conflict("该请假已销假，请刷新后重试");
        }
        audit("LEAVE_REVOKE", id, "note", text);
        return get(user, id);
    }

    /* ---------------- 班级导员配置 ---------------- */

    public ClassCounselorView counselors(AuthenticatedUser user, long classId) {
        Permissions.require(user, Permissions.LEAVE_MANAGE_ALL);
        SysOrgUnit clazz = requireClass(classId);
        List<Long> ids = counselorMapper.selectCounselorIds(classId);
        Map<Long, SysUser> users = new HashMap<>();
        if (!ids.isEmpty()) {
            userMapper.selectBatchIds(ids).forEach(u -> users.put(u.getId(), u));
        }
        return new ClassCounselorView(clazz.getId(), clazz.getCode(), clazz.getName(), ids,
                ids.stream().map(id -> users.get(id) == null ? null : users.get(id).getDisplayName())
                        .filter(Objects::nonNull).toList());
    }

    public List<ClassCounselorView> allCounselors(AuthenticatedUser user) {
        Permissions.require(user, Permissions.LEAVE_MANAGE_ALL);
        List<SysOrgUnit> classes = orgMapper.selectList(new LambdaQueryWrapper<SysOrgUnit>()
                .eq(SysOrgUnit::getType, OrgType.CLASS)
                .orderByAsc(SysOrgUnit::getCode));
        List<ClassCounselorView> result = new ArrayList<>();
        for (SysOrgUnit clazz : classes) {
            result.add(counselors(user, clazz.getId()));
        }
        return result;
    }

    @Transactional
    public ClassCounselorView setCounselors(AuthenticatedUser user, long classId, List<Long> counselorIds) {
        Permissions.require(user, Permissions.LEAVE_MANAGE_ALL);
        SysOrgUnit clazz = requireClass(classId);
        Set<Long> ids = new LinkedHashSet<>(counselorIds == null ? List.of() : counselorIds);
        for (Long id : ids) {
            SysUser counselor = userMapper.selectById(id);
            if (counselor == null || counselor.getStatus() != UserStatus.ACTIVE) {
                throw BusinessException.notFound("导员账号");
            }
        }
        counselorMapper.deleteByClass(classId);
        for (Long id : ids) {
            counselorMapper.insert(classId, id, user.userId());
        }
        audit("LEAVE_COUNSELOR_SET", classId, "classCode", clazz.getCode(), "counselors", ids);
        return counselors(user, classId);
    }

    private SysOrgUnit requireClass(long classId) {
        SysOrgUnit clazz = orgMapper.selectById(classId);
        if (clazz == null || clazz.getType() != OrgType.CLASS) {
            throw BusinessException.notFound("班级");
        }
        return clazz;
    }

    /* ---------------- 权限与视图 ---------------- */

    private boolean canActNow(AuthenticatedUser user, LeaveRequest leave) {
        if (leave.getStatus() != LeaveStatus.PENDING) {
            return false;
        }
        if (user.hasPermission(Permissions.LEAVE_MANAGE_ALL)) {
            return true;
        }
        if (!user.hasPermission(Permissions.LEAVE_REVIEW)) {
            return false;
        }
        String role = currentRole(leave);
        if (SettingService.ROLE_COUNSELOR.equals(role)) {
            return leave.getClassId() != null
                    && counselorMapper.count(leave.getClassId(), user.userId()) > 0;
        }
        return accessService.isTeacherOf(user, leave.getCourseId());
    }

    private boolean canView(AuthenticatedUser user, LeaveRequest leave) {
        if (Objects.equals(leave.getStudentId(), user.userId())) {
            return true;
        }
        if (user.hasPermission(Permissions.LEAVE_MANAGE_ALL) || user.hasRole(Roles.STUDENT_UNION)) {
            return true;
        }
        if (!user.hasPermission(Permissions.LEAVE_READ_ALL) && !user.hasPermission(Permissions.LEAVE_REVIEW)) {
            return false;
        }
        if (accessService.isTeacherOf(user, leave.getCourseId())) {
            return true;
        }
        return leave.getClassId() != null && counselorMapper.count(leave.getClassId(), user.userId()) > 0;
    }

    private String currentRole(LeaveRequest leave) {
        List<String> stages = stageRoles(leave);
        int index = leave.getCurrentStage() == null ? 0 : leave.getCurrentStage() - 1;
        return stages.get(Math.max(0, Math.min(index, stages.size() - 1)));
    }

    private List<String> stageRoles(LeaveRequest leave) {
        if (leave.getApprovalOrder() == null || leave.getApprovalOrder().isBlank()) {
            return List.of(SettingService.ROLE_TEACHER);
        }
        List<String> roles = Arrays.stream(leave.getApprovalOrder().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return roles.isEmpty() ? List.of(SettingService.ROLE_TEACHER) : roles;
    }

    private static LeaveApproverRole roleOf(String role) {
        return SettingService.ROLE_COUNSELOR.equals(role)
                ? LeaveApproverRole.COUNSELOR : LeaveApproverRole.TEACHER;
    }

    private static String roleLabel(String role) {
        return SettingService.ROLE_COUNSELOR.equals(role) ? "导员" : "任课教师";
    }

    private void applyFilters(LambdaQueryWrapper<LeaveRequest> w, LeaveQuery q) {
        if (q.getCourseId() != null) {
            w.eq(LeaveRequest::getCourseId, q.getCourseId());
        }
        if (q.getClassId() != null) {
            w.eq(LeaveRequest::getClassId, q.getClassId());
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
        return new PageResult<>(views(page.getRecords(), user), page.getTotal(), page.getCurrent(),
                page.getSize(), page.getPages());
    }

    /** 批量装配视图：课程、学生、审批人、班级名一次查完，避免逐条 N+1。 */
    private List<LeaveView> views(List<LeaveRequest> records, AuthenticatedUser viewer) {
        if (records.isEmpty()) {
            return List.of();
        }
        Set<Long> courseIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        Set<Long> classIds = new HashSet<>();
        Set<Long> leaveIds = new HashSet<>();
        for (LeaveRequest r : records) {
            courseIds.add(r.getCourseId());
            userIds.add(r.getStudentId());
            leaveIds.add(r.getId());
            if (r.getReviewerId() != null) {
                userIds.add(r.getReviewerId());
            }
            if (r.getClassId() != null) {
                classIds.add(r.getClassId());
            }
        }
        Map<Long, Course> courses = new HashMap<>();
        if (!courseIds.isEmpty()) {
            courseMapper.selectBatchIds(courseIds).forEach(c -> courses.put(c.getId(), c));
        }
        Map<Long, String> classNames = new HashMap<>();
        if (!classIds.isEmpty()) {
            orgMapper.selectBatchIds(classIds).forEach(o -> classNames.put(o.getId(), o.getName()));
        }
        List<LeaveApproval> approvals = approvalMapper.selectByLeaves(leaveIds);
        approvals.forEach(a -> userIds.add(a.getApproverId()));
        Map<Long, SysUser> users = new HashMap<>();
        if (!userIds.isEmpty()) {
            userMapper.selectBatchIds(userIds).forEach(u -> users.put(u.getId(), u));
        }
        Map<Long, List<LeaveApproval>> byLeave = new HashMap<>();
        approvals.forEach(a -> byLeave.computeIfAbsent(a.getLeaveId(), k -> new ArrayList<>()).add(a));

        List<LeaveView> items = new ArrayList<>();
        for (LeaveRequest leave : records) {
            items.add(toView(leave, viewer, courses, users, classNames,
                    byLeave.getOrDefault(leave.getId(), List.of())));
        }
        return items;
    }

    private LeaveView toView(LeaveRequest leave, AuthenticatedUser viewer, Map<Long, Course> courses,
                             Map<Long, SysUser> users, Map<Long, String> classNames,
                             List<LeaveApproval> approvals) {
        Course course = courses.get(leave.getCourseId());
        SysUser student = users.get(leave.getStudentId());
        if (student == null) {
            student = userMapper.selectById(leave.getStudentId());
        }
        SysUser reviewer = leave.getReviewerId() == null ? null : users.get(leave.getReviewerId());
        if (leave.getReviewerId() != null && reviewer == null) {
            reviewer = userMapper.selectById(leave.getReviewerId());
        }
        boolean canReview = canActNow(viewer, leave) && !Objects.equals(leave.getStudentId(), viewer.userId());
        boolean canCancel = leave.getStatus() == LeaveStatus.PENDING
                && Objects.equals(leave.getStudentId(), viewer.userId());
        boolean canRevoke = leave.getStatus() == LeaveStatus.APPROVED && leave.getRevokedAt() == null
                && Objects.equals(leave.getStudentId(), viewer.userId());
        List<LeaveApprovalView> approvalViews = approvals.stream()
                .map(a -> {
                    SysUser approver = users.get(a.getApproverId());
                    return new LeaveApprovalView(a.getStage(), a.getApproverRole(), a.getApproverId(),
                            approver == null ? null : approver.getDisplayName(),
                            a.getDecision(), a.getComment(), a.getDecidedAt());
                })
                .toList();
        return new LeaveView(leave.getId(), leave.getStudentId(),
                student == null ? null : student.getUsername(),
                student == null ? null : student.getDisplayName(),
                leave.getClassId(), classNames.get(leave.getClassId()),
                leave.getCourseId(), course == null ? null : course.getCode(),
                course == null ? null : course.getName(), leave.getScheduleId(),
                leave.getStartAt(), leave.getEndAt(), leave.getReason(), leave.getStatus(),
                stageRoles(leave), leave.getTotalStages() == null ? 1 : leave.getTotalStages(),
                leave.getCurrentStage() == null ? 1 : leave.getCurrentStage(),
                currentRole(leave), approvalViews,
                leave.getReviewerId(), reviewer == null ? null : reviewer.getDisplayName(),
                leave.getReviewComment(), leave.getReviewedAt(),
                leave.getRevokedAt(), leave.getRevokeNote(),
                canReview, canCancel, canRevoke,
                leave.getCreatedAt(), CourseService.value(leave.getVersion()));
    }

    private LeaveRequest requireLeave(long id) {
        LeaveRequest leave = leaveMapper.selectById(id);
        if (leave == null) {
            throw BusinessException.notFound("请假申请");
        }
        return leave;
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("LEAVE_REQUEST", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
