package com.campus.platform.attendance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.attendance.dto.AppealDecisionRequest;
import com.campus.platform.attendance.dto.AppealSubmitRequest;
import com.campus.platform.attendance.dto.AppealView;
import com.campus.platform.attendance.dto.MyAttendanceRow;
import com.campus.platform.attendance.dto.MyAttendanceView;
import com.campus.platform.attendance.entity.AppealStatus;
import com.campus.platform.attendance.entity.AttendanceAppeal;
import com.campus.platform.attendance.entity.AttendanceRecord;
import com.campus.platform.attendance.entity.AttendanceSession;
import com.campus.platform.attendance.entity.AttendanceStatus;
import com.campus.platform.attendance.mapper.AttendanceAppealMapper;
import com.campus.platform.attendance.mapper.AttendanceQueryMapper;
import com.campus.platform.attendance.mapper.AttendanceRecordMapper;
import com.campus.platform.attendance.mapper.AttendanceSessionMapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.mapper.ClassCounselorMapper;
import com.campus.platform.course.mapper.CourseMapper;
import com.campus.platform.course.service.CourseAccessService;
import com.campus.platform.notification.entity.NotificationCategory;
import com.campus.platform.notification.entity.NotificationLevel;
import com.campus.platform.notification.service.NotificationService;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.security.Roles;
import com.campus.platform.setting.service.SettingService;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 考勤申诉：学生对「旷课/迟到」提出异议，任课教师、导员或管理员复核。
 * <p>
 * 一条明细只能申诉一次；通过后把明细改为出勤/迟到/请假，并通知学生。
 * 申诉期限由 {@code attendance.appeal.window.days} 控制，防止学期末集中翻旧账。
 */
@Service
public class AttendanceAppealService {

    static final String MODULE = "attendance";

    private final AttendanceAppealMapper appealMapper;
    private final AttendanceRecordMapper recordMapper;
    private final AttendanceSessionMapper sessionMapper;
    private final AttendanceQueryMapper queryMapper;
    private final CourseMapper courseMapper;
    private final SysUserMapper userMapper;
    private final ClassCounselorMapper counselorMapper;
    private final CourseAccessService accessService;
    private final SettingService settingService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    public AttendanceAppealService(AttendanceAppealMapper appealMapper, AttendanceRecordMapper recordMapper,
                                   AttendanceSessionMapper sessionMapper, AttendanceQueryMapper queryMapper,
                                   CourseMapper courseMapper, SysUserMapper userMapper,
                                   ClassCounselorMapper counselorMapper, CourseAccessService accessService,
                                   SettingService settingService, NotificationService notificationService,
                                   AuditService auditService, Clock clock) {
        this.appealMapper = appealMapper;
        this.recordMapper = recordMapper;
        this.sessionMapper = sessionMapper;
        this.queryMapper = queryMapper;
        this.courseMapper = courseMapper;
        this.userMapper = userMapper;
        this.counselorMapper = counselorMapper;
        this.accessService = accessService;
        this.settingService = settingService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** 我的考勤：列出本人明细，并标记哪些还能申诉。 */
    public List<MyAttendanceView> myAttendance(AuthenticatedUser user, AttendanceStatus status) {
        Permissions.require(user, Permissions.ATTENDANCE_APPEAL);
        List<MyAttendanceRow> rows = queryMapper.selectStudentRecords(user.userId(),
                status == null ? null : status.name(), 100);
        int window = windowDays();
        LocalDate earliest = LocalDate.now(clock).minusDays(window);
        List<MyAttendanceView> views = new ArrayList<>();
        for (MyAttendanceRow row : rows) {
            boolean canAppeal = (row.getStatus() == AttendanceStatus.ABSENT || row.getStatus() == AttendanceStatus.LATE)
                    && row.getAppealId() == null
                    && row.getSessionDate() != null
                    && !row.getSessionDate().isBefore(earliest);
            views.add(new MyAttendanceView(row.getRecordId(), row.getCourseId(), row.getCourseCode(),
                    row.getCourseName(), row.getSessionDate(), value(row.getStartPeriod()),
                    value(row.getEndPeriod()), row.getStatus(), row.getRemark(), row.getAppealId(),
                    row.getAppealStatus(), canAppeal));
        }
        return views;
    }

    @Transactional
    public AppealView submit(AuthenticatedUser user, AppealSubmitRequest req) {
        Permissions.require(user, Permissions.ATTENDANCE_APPEAL);
        AttendanceRecord record = recordMapper.selectById(req.recordId());
        if (record == null) {
            throw BusinessException.notFound("考勤记录");
        }
        if (!Objects.equals(record.getStudentId(), user.userId())) {
            throw BusinessException.forbidden("只能对本人的考勤提出申诉");
        }
        if (record.getStatus() != AttendanceStatus.ABSENT && record.getStatus() != AttendanceStatus.LATE) {
            throw BusinessException.rule("只有旷课或迟到可以申诉");
        }
        AttendanceSession session = sessionMapper.selectById(record.getSessionId());
        if (session == null) {
            throw BusinessException.notFound("考勤场次");
        }
        if (session.getSessionDate().isBefore(LocalDate.now(clock).minusDays(windowDays()))) {
            throw BusinessException.rule("已超过申诉期限（" + windowDays() + " 天）");
        }
        if (appealMapper.selectByRecord(record.getId()) != null) {
            throw BusinessException.conflict("这条考勤已经申诉过了");
        }
        AttendanceStatus target = req.targetStatus() == null ? AttendanceStatus.PRESENT : req.targetStatus();
        if (target == AttendanceStatus.ABSENT) {
            throw BusinessException.badRequest("申诉目标不能是旷课");
        }
        AttendanceAppeal appeal = new AttendanceAppeal();
        appeal.setRecordId(record.getId());
        appeal.setStudentId(user.userId());
        appeal.setReason(req.reason() == null || req.reason().isBlank() ? "对考勤有异议" : req.reason().trim());
        appeal.setTargetStatus(target);
        appeal.setStatus(AppealStatus.PENDING);
        appeal.setVersion(0);
        appealMapper.insert(appeal);

        Course course = courseMapper.selectById(session.getCourseId());
        String courseName = course == null ? "课程" : course.getName();
        Set<Long> recipients = new HashSet<>(accessService.teacherIdsOf(session.getCourseId()));
        if (session.getClassId() != null) {
            recipients.addAll(counselorMapper.selectCounselorIds(session.getClassId()));
        }
        notificationService.notifyAll(recipients, NotificationCategory.ATTENDANCE, NotificationLevel.WARNING,
                "有新的考勤申诉", user.displayName() + " 对《" + courseName + "》" + session.getSessionDate()
                        + " 第 " + session.getStartPeriod() + "–" + session.getEndPeriod() + " 节的考勤提出申诉",
                "/leave?tab=appeals", "ATTENDANCE_APPEAL", appeal.getId());
        audit("ATTENDANCE_APPEAL_SUBMIT", appeal.getId(), "recordId", record.getId());
        return view(appeal, user, Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** 我提交过的申诉。 */
    public List<AppealView> mine(AuthenticatedUser user) {
        Permissions.require(user, Permissions.ATTENDANCE_APPEAL);
        List<AttendanceAppeal> appeals = appealMapper.selectList(new LambdaQueryWrapper<AttendanceAppeal>()
                .eq(AttendanceAppeal::getStudentId, user.userId())
                .orderByDesc(AttendanceAppeal::getId)
                .last("LIMIT 100"));
        return views(appeals, user);
    }

    /** 待我复核：管理员全部，教师任教课程，导员所带班级。 */
    public List<AppealView> reviewList(AuthenticatedUser user, AppealStatus status) {
        Permissions.require(user, Permissions.ATTENDANCE_MANAGE);
        List<AttendanceAppeal> appeals = appealMapper.selectList(new LambdaQueryWrapper<AttendanceAppeal>()
                .eq(AttendanceAppeal::getStatus, status == null ? AppealStatus.PENDING : status)
                .orderByDesc(AttendanceAppeal::getId)
                .last("LIMIT 200"));
        return views(appeals, user).stream().filter(AppealView::canReview).toList();
    }

    @Transactional
    public AppealView decide(AuthenticatedUser user, long id, AppealDecisionRequest req) {
        Permissions.require(user, Permissions.ATTENDANCE_MANAGE);
        AttendanceAppeal appeal = appealMapper.selectById(id);
        if (appeal == null) {
            throw BusinessException.notFound("考勤申诉");
        }
        if (appeal.getStatus() != AppealStatus.PENDING) {
            throw BusinessException.conflict("该申诉已处理");
        }
        AttendanceRecord record = recordMapper.selectById(appeal.getRecordId());
        AttendanceSession session = record == null ? null : sessionMapper.selectById(record.getSessionId());
        if (record == null || session == null) {
            throw BusinessException.notFound("考勤记录");
        }
        if (!canReview(user, session)) {
            throw BusinessException.forbidden("只能复核本人任教课程或所带班级的考勤");
        }
        String comment = req.comment() == null || req.comment().isBlank() ? null : req.comment().trim();
        boolean approve = Boolean.TRUE.equals(req.approve());
        String target = approve
                ? (req.targetStatus() == null ? appeal.getTargetStatus() : req.targetStatus()).name()
                : null;
        if (appealMapper.decide(id, approve ? "APPROVED" : "REJECTED", user.userId(), comment,
                LocalDateTime.now(clock)) != 1) {
            throw BusinessException.conflict("该申诉已被其他人处理");
        }
        if (approve) {
            String remark = comment == null ? "申诉通过" : "申诉通过：" + comment;
            recordMapper.updateStatus(record.getId(), target, remark, LocalDateTime.now(clock));
        }
        Course course = courseMapper.selectById(session.getCourseId());
        String courseName = course == null ? "课程" : course.getName();
        notificationService.notify(appeal.getStudentId(), NotificationCategory.ATTENDANCE,
                approve ? NotificationLevel.SUCCESS : NotificationLevel.WARNING,
                approve ? "考勤申诉已通过" : "考勤申诉未通过",
                "《" + courseName + "》" + session.getSessionDate() + " 的考勤申诉"
                        + (approve ? "已改为" + target : "被驳回") + (comment == null ? "" : "：" + comment),
                "/leave?tab=mine-attendance", "ATTENDANCE_APPEAL", appeal.getId());
        audit(approve ? "ATTENDANCE_APPEAL_APPROVE" : "ATTENDANCE_APPEAL_REJECT", id,
                "recordId", record.getId(), "target", target);
        return view(appealMapper.selectById(id), user, Map.of(), Map.of(), Map.of(), Map.of());
    }

    /* ---------------- 内部 ---------------- */

    private List<AppealView> views(List<AttendanceAppeal> appeals, AuthenticatedUser viewer) {
        if (appeals.isEmpty()) {
            return List.of();
        }
        Set<Long> recordIds = new HashSet<>();
        Set<Long> studentIds = new HashSet<>();
        Set<Long> reviewerIds = new HashSet<>();
        appeals.forEach(a -> {
            recordIds.add(a.getRecordId());
            studentIds.add(a.getStudentId());
            if (a.getReviewerId() != null) {
                reviewerIds.add(a.getReviewerId());
            }
        });
        Map<Long, AttendanceRecord> records = new HashMap<>();
        recordMapper.selectBatchIds(recordIds).forEach(r -> records.put(r.getId(), r));
        Set<Long> sessionIds = new HashSet<>();
        records.values().forEach(r -> sessionIds.add(r.getSessionId()));
        Map<Long, AttendanceSession> sessions = new HashMap<>();
        if (!sessionIds.isEmpty()) {
            sessionMapper.selectBatchIds(sessionIds).forEach(s -> sessions.put(s.getId(), s));
        }
        Set<Long> courseIds = new HashSet<>();
        sessions.values().forEach(s -> courseIds.add(s.getCourseId()));
        Map<Long, Course> courses = new HashMap<>();
        if (!courseIds.isEmpty()) {
            courseMapper.selectBatchIds(courseIds).forEach(c -> courses.put(c.getId(), c));
        }
        Map<Long, SysUser> users = new HashMap<>();
        Set<Long> userIds = new HashSet<>(studentIds);
        userIds.addAll(reviewerIds);
        if (!userIds.isEmpty()) {
            userMapper.selectBatchIds(userIds).forEach(u -> users.put(u.getId(), u));
        }
        List<AppealView> views = new ArrayList<>();
        for (AttendanceAppeal appeal : appeals) {
            views.add(view(appeal, viewer, records, sessions, courses, users));
        }
        return views;
    }

    private AppealView view(AttendanceAppeal appeal, AuthenticatedUser viewer,
                            Map<Long, AttendanceRecord> records, Map<Long, AttendanceSession> sessions,
                            Map<Long, Course> courses, Map<Long, SysUser> users) {
        AttendanceRecord record = records.get(appeal.getRecordId());
        if (record == null) {
            record = recordMapper.selectById(appeal.getRecordId());
        }
        AttendanceSession session = record == null ? null : sessions.get(record.getSessionId());
        if (record != null && session == null) {
            session = sessionMapper.selectById(record.getSessionId());
        }
        Course course = session == null ? null : courses.get(session.getCourseId());
        if (session != null && course == null) {
            course = courseMapper.selectById(session.getCourseId());
        }
        SysUser student = users.get(appeal.getStudentId());
        if (student == null) {
            student = userMapper.selectById(appeal.getStudentId());
        }
        SysUser reviewer = appeal.getReviewerId() == null ? null : users.get(appeal.getReviewerId());
        if (appeal.getReviewerId() != null && reviewer == null) {
            reviewer = userMapper.selectById(appeal.getReviewerId());
        }
        boolean canReview = appeal.getStatus() == AppealStatus.PENDING
                && session != null && canReview(viewer, session);
        return new AppealView(appeal.getId(), appeal.getRecordId(), appeal.getStudentId(),
                student == null ? null : student.getDisplayName(),
                student == null ? null : student.getUsername(),
                session == null ? null : session.getCourseId(),
                course == null ? null : course.getName(),
                session == null ? null : session.getSessionDate(),
                session == null ? 0 : value(session.getStartPeriod()),
                session == null ? 0 : value(session.getEndPeriod()),
                record == null ? null : record.getStatus(),
                appeal.getTargetStatus(), appeal.getReason(), appeal.getStatus(),
                reviewer == null ? null : reviewer.getDisplayName(),
                appeal.getReviewComment(), appeal.getReviewedAt(), canReview, appeal.getCreatedAt());
    }

    private boolean canReview(AuthenticatedUser user, AttendanceSession session) {
        if (user.hasRole(Roles.ADMIN)) {
            return true;
        }
        if (accessService.isTeacherOf(user, session.getCourseId())) {
            return true;
        }
        return session.getClassId() != null && counselorMapper.count(session.getClassId(), user.userId()) > 0;
    }

    private int windowDays() {
        String raw = settingService.get("attendance.appeal.window.days", "7");
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 7;
        }
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("ATTENDANCE_APPEAL", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
