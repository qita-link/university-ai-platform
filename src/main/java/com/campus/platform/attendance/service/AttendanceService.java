package com.campus.platform.attendance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.attendance.dto.AbsenceCountRow;
import com.campus.platform.attendance.dto.AttendanceCreateRequest;
import com.campus.platform.attendance.dto.AttendanceRecordItem;
import com.campus.platform.attendance.dto.AttendanceRecordView;
import com.campus.platform.attendance.dto.AttendanceSessionDetailView;
import com.campus.platform.attendance.dto.AttendanceSessionView;
import com.campus.platform.attendance.dto.ClassAttendanceStat;
import com.campus.platform.attendance.dto.ClassAttendanceStatsView;
import com.campus.platform.attendance.dto.ClassOptionView;
import com.campus.platform.attendance.dto.ClassTimetableCourse;
import com.campus.platform.attendance.dto.ClassTimetableSlot;
import com.campus.platform.attendance.dto.ClassTimetableView;
import com.campus.platform.attendance.dto.SessionCountRow;
import com.campus.platform.attendance.dto.StudentStatusCountRow;
import com.campus.platform.attendance.entity.AttendanceRecord;
import com.campus.platform.attendance.entity.AttendanceSession;
import com.campus.platform.attendance.entity.AttendanceSessionStatus;
import com.campus.platform.attendance.entity.AttendanceStatus;
import com.campus.platform.attendance.entity.AttendanceWarning;
import com.campus.platform.attendance.mapper.AttendanceQueryMapper;
import com.campus.platform.attendance.mapper.AttendanceRecordMapper;
import com.campus.platform.attendance.mapper.AttendanceSessionMapper;
import com.campus.platform.attendance.mapper.AttendanceWarningMapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.CourseSchedule;
import com.campus.platform.course.entity.CourseTeacher;
import com.campus.platform.course.entity.LeaveRequest;
import com.campus.platform.course.entity.LeaveStatus;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.entity.SemesterStatus;
import com.campus.platform.course.mapper.ClassCounselorMapper;
import com.campus.platform.course.mapper.CourseMapper;
import com.campus.platform.course.mapper.CourseScheduleMapper;
import com.campus.platform.course.mapper.CourseTeacherMapper;
import com.campus.platform.course.mapper.LeaveRequestMapper;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.course.service.CourseAccessService;
import com.campus.platform.course.service.CourseService;
import com.campus.platform.notification.entity.NotificationCategory;
import com.campus.platform.notification.entity.NotificationLevel;
import com.campus.platform.notification.service.NotificationService;
import com.campus.platform.setting.service.SettingService;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.security.Roles;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 班级考勤：按「班级 + 课程 + 日期 + 节次」建场次，逐人登记出勤，统计旷课。
 * <p>
 * 录入权限：任课教师、班级导员与管理员；查看权限：学生会、教师、管理员（attendance:read）。
 * 新建场次时自动把当天已通过的请假标为 LEAVE，避免把请假误记为旷课。
 */
@Service
public class AttendanceService {

    static final String MODULE = "attendance";

    private final AttendanceSessionMapper sessionMapper;
    private final AttendanceRecordMapper recordMapper;
    private final AttendanceQueryMapper queryMapper;
    private final CourseMapper courseMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final CourseTeacherMapper teacherMapper;
    private final SemesterMapper semesterMapper;
    private final LeaveRequestMapper leaveMapper;
    private final SysUserMapper userMapper;
    private final SysOrgUnitMapper orgMapper;
    private final ClassCounselorMapper counselorMapper;
    private final CourseService courseService;
    private final CourseAccessService accessService;
    private final AttendanceWarningMapper warningMapper;
    private final SettingService settingService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    public AttendanceService(AttendanceSessionMapper sessionMapper, AttendanceRecordMapper recordMapper,
                             AttendanceQueryMapper queryMapper, CourseMapper courseMapper,
                             CourseScheduleMapper scheduleMapper, CourseTeacherMapper teacherMapper,
                             SemesterMapper semesterMapper, LeaveRequestMapper leaveMapper, SysUserMapper userMapper,
                             SysOrgUnitMapper orgMapper, ClassCounselorMapper counselorMapper,
                             CourseService courseService, CourseAccessService accessService,
                             AttendanceWarningMapper warningMapper, SettingService settingService,
                             NotificationService notificationService, AuditService auditService, Clock clock) {
        this.sessionMapper = sessionMapper;
        this.recordMapper = recordMapper;
        this.queryMapper = queryMapper;
        this.courseMapper = courseMapper;
        this.scheduleMapper = scheduleMapper;
        this.teacherMapper = teacherMapper;
        this.semesterMapper = semesterMapper;
        this.leaveMapper = leaveMapper;
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.counselorMapper = counselorMapper;
        this.courseService = courseService;
        this.accessService = accessService;
        this.warningMapper = warningMapper;
        this.settingService = settingService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /* ---------------- 基础数据 ---------------- */

    public List<ClassOptionView> classes(AuthenticatedUser user) {
        Permissions.requireAny(user, Permissions.ATTENDANCE_READ, Permissions.ATTENDANCE_MANAGE);
        return orgMapper.selectList(new LambdaQueryWrapper<SysOrgUnit>()
                        .eq(SysOrgUnit::getType, OrgType.CLASS)
                        .orderByAsc(SysOrgUnit::getCode))
                .stream()
                .map(o -> new ClassOptionView(o.getId(), o.getCode(), o.getName()))
                .toList();
    }

    public List<Semester> semesters() {
        return semesterMapper.selectList(new LambdaQueryWrapper<Semester>()
                .orderByDesc(Semester::getStartDate));
    }

    /* ---------------- 班级课表与旷课 ---------------- */

    public ClassTimetableView classTimetable(AuthenticatedUser user, long classId, Long semesterId) {
        Permissions.requireAny(user, Permissions.ATTENDANCE_READ, Permissions.ATTENDANCE_MANAGE);
        SysOrgUnit clazz = requireClass(classId);
        Semester semester = resolveSemester(semesterId);
        Long sid = semester == null ? null : semester.getId();

        List<Long> courseIds = queryMapper.selectCourseIdsByClass(classId, sid);
        List<ClassTimetableCourse> courses = new ArrayList<>();
        int totalSessions = 0;
        int totalAbsences = 0;
        if (!courseIds.isEmpty()) {
            Map<Long, Course> courseMap = new HashMap<>();
            courseMapper.selectBatchIds(courseIds).forEach(c -> courseMap.put(c.getId(), c));
            Map<Long, List<CourseSchedule>> schedules = new HashMap<>();
            scheduleMapper.selectByCourses(courseIds)
                    .forEach(s -> schedules.computeIfAbsent(s.getCourseId(), k -> new ArrayList<>()).add(s));
            Map<Long, List<String>> teacherNames = teacherNames(courseIds);
            Map<Long, Integer> sessionByCourse = new HashMap<>();
            Map<Long, Integer> absentByCourse = new HashMap<>();
            Map<Long, Integer> sessionBySlot = new HashMap<>();
            Map<Long, Integer> absentBySlot = new HashMap<>();
            for (SessionCountRow row : queryMapper.selectSessionCounts(classId, sid)) {
                sessionByCourse.merge(row.getCourseId(), value(row.getSessionCount()), Integer::sum);
                if (row.getScheduleId() != null) {
                    sessionBySlot.merge(row.getScheduleId(), value(row.getSessionCount()), Integer::sum);
                }
            }
            for (AbsenceCountRow row : queryMapper.selectAbsenceCounts(classId, sid)) {
                absentByCourse.merge(row.getCourseId(), value(row.getAbsentCount()), Integer::sum);
                if (row.getScheduleId() != null) {
                    absentBySlot.merge(row.getScheduleId(), value(row.getAbsentCount()), Integer::sum);
                }
            }
            for (Long courseId : courseIds) {
                Course course = courseMap.get(courseId);
                if (course == null) {
                    continue;
                }
                List<ClassTimetableSlot> slots = schedules.getOrDefault(courseId, List.of()).stream()
                        .map(s -> new ClassTimetableSlot(s.getId(), value(s.getDayOfWeek()),
                                value(s.getStartPeriod()), value(s.getEndPeriod()),
                                value(s.getStartWeek()), value(s.getEndWeek()),
                                s.getWeekParity() == null ? "ALL" : s.getWeekParity().name(),
                                s.getClassroom(),
                                sessionBySlot.getOrDefault(s.getId(), 0),
                                absentBySlot.getOrDefault(s.getId(), 0)))
                        .toList();
                int sessionCount = sessionByCourse.getOrDefault(courseId, 0);
                int absentCount = absentByCourse.getOrDefault(courseId, 0);
                totalSessions += sessionCount;
                totalAbsences += absentCount;
                courses.add(new ClassTimetableCourse(course.getId(), course.getCode(), course.getName(),
                        teacherNames.getOrDefault(courseId, List.of()), slots, sessionCount, absentCount));
            }
        }
        return new ClassTimetableView(clazz.getId(), clazz.getCode(), clazz.getName(),
                semester == null ? null : semester.getId(),
                semester == null ? null : semester.getName(),
                totalSessions, totalAbsences, courses);
    }

    public ClassAttendanceStatsView classStats(AuthenticatedUser user, long classId, Long semesterId,
                                               Long courseId) {
        Permissions.requireAny(user, Permissions.ATTENDANCE_READ, Permissions.ATTENDANCE_MANAGE);
        SysOrgUnit clazz = requireClass(classId);
        Semester semester = resolveSemester(semesterId);
        Long sid = semester == null ? null : semester.getId();

        List<SysUser> students = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getOrgUnitId, classId)
                .eq(SysUser::getStatus, UserStatus.ACTIVE)
                .orderByAsc(SysUser::getUsername));
        Map<Long, Map<AttendanceStatus, Integer>> counts = new HashMap<>();
        for (StudentStatusCountRow row : queryMapper.selectStudentCounts(classId, sid, courseId)) {
            counts.computeIfAbsent(row.getStudentId(), k -> new HashMap<>())
                    .merge(row.getStatus(), value(row.getCnt()), Integer::sum);
        }
        List<SessionCountRow> sessionCounts = queryMapper.selectSessionCounts(classId, sid);
        int sessionCount = sessionCounts.stream().mapToInt(r -> value(r.getSessionCount())).sum();

        List<ClassAttendanceStat> stats = new ArrayList<>();
        for (SysUser student : students) {
            Map<AttendanceStatus, Integer> c = counts.getOrDefault(student.getId(), Map.of());
            int absent = c.getOrDefault(AttendanceStatus.ABSENT, 0);
            int present = c.getOrDefault(AttendanceStatus.PRESENT, 0);
            int late = c.getOrDefault(AttendanceStatus.LATE, 0);
            int leave = c.getOrDefault(AttendanceStatus.LEAVE, 0);
            int total = present + absent + late + leave;
            double rate = total == 0 ? 0
                    : BigDecimal.valueOf(absent * 100.0 / total).setScale(1, RoundingMode.HALF_UP).doubleValue();
            stats.add(new ClassAttendanceStat(student.getId(), student.getUsername(),
                    student.getDisplayName(), total, present, absent, late, leave, rate));
        }
        stats.sort(Comparator.comparingInt(ClassAttendanceStat::absent).reversed()
                .thenComparing(ClassAttendanceStat::displayName));
        return new ClassAttendanceStatsView(clazz.getId(), clazz.getName(),
                semester == null ? null : semester.getId(),
                semester == null ? null : semester.getName(), sessionCount, stats);
    }

    /* ---------------- 考勤场次 ---------------- */

    public List<AttendanceSessionView> sessions(AuthenticatedUser user, long classId, Long courseId,
                                                LocalDate from, LocalDate to) {
        Permissions.requireAny(user, Permissions.ATTENDANCE_READ, Permissions.ATTENDANCE_MANAGE);
        requireClass(classId);
        List<AttendanceSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<AttendanceSession>()
                .eq(AttendanceSession::getClassId, classId)
                .eq(courseId != null, AttendanceSession::getCourseId, courseId)
                .ge(from != null, AttendanceSession::getSessionDate, from)
                .le(to != null, AttendanceSession::getSessionDate, to)
                .orderByDesc(AttendanceSession::getSessionDate)
                .orderByDesc(AttendanceSession::getId)
                .last("LIMIT 200"));
        if (sessions.isEmpty()) {
            return List.of();
        }
        Map<Long, Course> courses = new HashMap<>();
        Set<Long> courseIds = new HashSet<>();
        sessions.forEach(s -> courseIds.add(s.getCourseId()));
        courseMapper.selectBatchIds(courseIds).forEach(c -> courses.put(c.getId(), c));
        Map<Long, String> classNames = new HashMap<>();
        orgMapper.selectBatchIds(Set.of(classId)).forEach(o -> classNames.put(o.getId(), o.getName()));
        List<Long> ids = sessions.stream().map(AttendanceSession::getId).toList();
        Map<Long, List<AttendanceRecord>> records = new HashMap<>();
        recordMapper.selectBySessions(ids).forEach(r ->
                records.computeIfAbsent(r.getSessionId(), k -> new ArrayList<>()).add(r));
        List<AttendanceSessionView> views = new ArrayList<>();
        for (AttendanceSession session : sessions) {
            views.add(toSessionView(session, courses.get(session.getCourseId()),
                    classNames.get(session.getClassId()), records.getOrDefault(session.getId(), List.of())));
        }
        return views;
    }

    @Transactional
    public AttendanceSessionDetailView create(AuthenticatedUser user, AttendanceCreateRequest req) {
        Permissions.require(user, Permissions.ATTENDANCE_MANAGE);
        Course course = courseService.requireCourse(req.courseId());
        SysOrgUnit clazz = requireClass(req.classId());
        requireManageable(user, course.getId(), clazz.getId());
        int endPeriod = req.endPeriod() == null ? req.startPeriod() : req.endPeriod();
        if (endPeriod < req.startPeriod()) {
            throw BusinessException.badRequest("结束节次不能早于开始节次");
        }
        if (req.scheduleId() != null) {
            CourseSchedule schedule = scheduleMapper.selectById(req.scheduleId());
            if (schedule == null || !Objects.equals(schedule.getCourseId(), course.getId())) {
                throw BusinessException.badRequest("所选排课不属于该课程");
            }
        }
        AttendanceSession existing = sessionMapper.selectSlot(course.getId(), clazz.getId(),
                req.sessionDate(), req.startPeriod());
        if (existing != null) {
            throw BusinessException.conflict("该班这节课已有考勤记录，请直接编辑");
        }

        AttendanceSession session = new AttendanceSession();
        session.setCourseId(course.getId());
        session.setScheduleId(req.scheduleId());
        session.setClassId(clazz.getId());
        session.setSessionDate(req.sessionDate());
        session.setStartPeriod(req.startPeriod());
        session.setEndPeriod(endPeriod);
        session.setStatus(AttendanceSessionStatus.DRAFT);
        session.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        session.setVersion(0);
        try {
            sessionMapper.insert(session);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("该班这节课已有考勤记录，请直接编辑");
        }

        List<SysUser> students = classStudents(clazz.getId());
        Map<Long, Long> leaveByStudent = approvedLeaves(students.stream().map(SysUser::getId).toList(),
                course.getId(), req.sessionDate());
        for (SysUser student : students) {
            AttendanceRecord record = new AttendanceRecord();
            record.setSessionId(session.getId());
            record.setStudentId(student.getId());
            Long leaveId = leaveByStudent.get(student.getId());
            record.setStatus(leaveId == null ? AttendanceStatus.PRESENT : AttendanceStatus.LEAVE);
            record.setLeaveId(leaveId);
            recordMapper.insert(record);
        }
        audit("ATTENDANCE_CREATE", session.getId(), "courseId", course.getId(), "classId", clazz.getId(),
                "date", req.sessionDate(), "period", req.startPeriod());
        return detail(user, session.getId());
    }

    public AttendanceSessionDetailView detail(AuthenticatedUser user, long id) {
        Permissions.requireAny(user, Permissions.ATTENDANCE_READ, Permissions.ATTENDANCE_MANAGE);
        AttendanceSession session = requireSession(id);
        Course course = courseMapper.selectById(session.getCourseId());
        SysOrgUnit clazz = orgMapper.selectById(session.getClassId());
        List<AttendanceRecord> records = recordMapper.selectBySession(id);
        Map<Long, SysUser> students = new HashMap<>();
        if (!records.isEmpty()) {
            userMapper.selectBatchIds(records.stream().map(AttendanceRecord::getStudentId).toList())
                    .forEach(u -> students.put(u.getId(), u));
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
        List<AttendanceRecordView> views = records.stream()
                .map(r -> {
                    SysUser student = students.get(r.getStudentId());
                    return new AttendanceRecordView(r.getStudentId(),
                            student == null ? null : student.getUsername(),
                            student == null ? null : student.getDisplayName(),
                            student == null ? null : orgNames.get(student.getOrgUnitId()),
                            r.getStatus(), r.getLeaveId(), r.getRemark());
                })
                .toList();
        return new AttendanceSessionDetailView(
                toSessionView(session, course, clazz == null ? null : clazz.getName(), records), views);
    }

    @Transactional
    public AttendanceSessionDetailView save(AuthenticatedUser user, long id, List<AttendanceRecordItem> items) {
        Permissions.require(user, Permissions.ATTENDANCE_MANAGE);
        AttendanceSession session = requireSession(id);
        requireManageable(user, session.getCourseId(), session.getClassId());
        if (session.getStatus() == AttendanceSessionStatus.FINAL && !user.hasRole(Roles.ADMIN)) {
            throw BusinessException.rule("考勤已提交，如需修改请联系管理员");
        }
        Map<Long, AttendanceRecord> existing = new HashMap<>();
        recordMapper.selectBySession(id).forEach(r -> existing.put(r.getStudentId(), r));
        for (AttendanceRecordItem item : items) {
            AttendanceRecord record = existing.get(item.studentId());
            if (record == null) {
                throw BusinessException.badRequest("学生不属于该班级的本次考勤");
            }
            record.setStatus(item.status());
            record.setRemark(item.remark() == null || item.remark().isBlank() ? null : item.remark().trim());
            recordMapper.updateById(record);
        }
        audit("ATTENDANCE_SAVE", id, "count", items.size());
        if (session.getStatus() == AttendanceSessionStatus.FINAL) {
            evaluateWarnings(session);
        }
        return detail(user, id);
    }

    @Transactional
    public AttendanceSessionDetailView finalizeSession(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.ATTENDANCE_MANAGE);
        AttendanceSession session = requireSession(id);
        requireManageable(user, session.getCourseId(), session.getClassId());
        if (session.getStatus() == AttendanceSessionStatus.FINAL) {
            return detail(user, id);
        }
        session.setStatus(AttendanceSessionStatus.FINAL);
        sessionMapper.updateById(session);
        audit("ATTENDANCE_FINALIZE", id);
        evaluateWarnings(session);
        return detail(user, id);
    }

    /**
     * 同步名单：场次创建后新加入班级的学生不会自动出现在旧场次里，
     * 由教师手动同步；已通过请假仍会自动标为 LEAVE。
     */
    @Transactional
    public AttendanceSessionDetailView syncRoster(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.ATTENDANCE_MANAGE);
        AttendanceSession session = requireSession(id);
        requireManageable(user, session.getCourseId(), session.getClassId());
        List<SysUser> students = classStudents(session.getClassId());
        Set<Long> existing = new HashSet<>();
        recordMapper.selectBySession(id).forEach(r -> existing.add(r.getStudentId()));
        Map<Long, Long> leaves = approvedLeaves(students.stream().map(SysUser::getId).toList(),
                session.getCourseId(), session.getSessionDate());
        int added = 0;
        for (SysUser student : students) {
            if (existing.contains(student.getId())) {
                continue;
            }
            AttendanceRecord record = new AttendanceRecord();
            record.setSessionId(id);
            record.setStudentId(student.getId());
            Long leaveId = leaves.get(student.getId());
            record.setStatus(leaveId == null ? AttendanceStatus.PRESENT : AttendanceStatus.LEAVE);
            record.setLeaveId(leaveId);
            recordMapper.insert(record);
            added++;
        }
        audit("ATTENDANCE_SYNC_ROSTER", id, "added", added);
        return detail(user, id);
    }

    /**
     * 缺勤预警：同一课程累计旷课达到阈值时，提醒学生与导员。
     * 以 (学生, 课程, 次数) 去重，同一门槛只提醒一次，避免每次课都轰炸。
     */
    private void evaluateWarnings(AttendanceSession session) {
        if (session.getStatus() != AttendanceSessionStatus.FINAL) {
            return;
        }
        int threshold = intSetting("attendance.warning.threshold", 3);
        if (threshold <= 0) {
            return;
        }
        Course course = courseMapper.selectById(session.getCourseId());
        if (course == null) {
            return;
        }
        Map<Long, String> names = new HashMap<>();
        List<AttendanceRecord> records = recordMapper.selectBySession(session.getId());
        for (AttendanceRecord record : records) {
            if (record.getStatus() != AttendanceStatus.ABSENT) {
                continue;
            }
            long count = recordMapper.countAbsent(record.getStudentId(), session.getCourseId());
            if (count < threshold) {
                continue;
            }
            AttendanceWarning warning = new AttendanceWarning();
            warning.setStudentId(record.getStudentId());
            warning.setCourseId(session.getCourseId());
            warning.setAbsentCount((int) count);
            try {
                warningMapper.insert(warning);
            } catch (DuplicateKeyException e) {
                continue;
            }
            String studentName = names.computeIfAbsent(record.getStudentId(), key -> {
                SysUser student = userMapper.selectById(key);
                return student == null ? "学生" : student.getDisplayName();
            });
            notificationService.notify(record.getStudentId(), NotificationCategory.ATTENDANCE,
                    NotificationLevel.WARNING, "缺勤提醒",
                    "你在《" + course.getName() + "》已累计旷课 " + count + " 次，请及时向任课教师说明情况。",
                    "/leave?tab=mine-attendance", "ATTENDANCE_WARNING", warning.getId());
            if (session.getClassId() != null) {
                List<Long> counselors = counselorMapper.selectCounselorIds(session.getClassId());
                notificationService.notifyAll(counselors, NotificationCategory.ATTENDANCE,
                        NotificationLevel.WARNING, "学生缺勤预警",
                        studentName + " 在《" + course.getName() + "》累计旷课 " + count + " 次。",
                        "/leave?tab=attendance", "ATTENDANCE_WARNING", warning.getId());
            }
        }
    }

    private int intSetting(String key, int fallback) {
        try {
            return Integer.parseInt(settingService.get(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /* ---------------- 内部 ---------------- */

    private boolean canManage(AuthenticatedUser user, long courseId, long classId) {
        if (user.hasRole(Roles.ADMIN)) {
            return true;
        }
        if (accessService.isTeacherOf(user, courseId)) {
            return true;
        }
        return counselorMapper.count(classId, user.userId()) > 0;
    }

    private void requireManageable(AuthenticatedUser user, long courseId, long classId) {
        if (!canManage(user, courseId, classId)) {
            throw BusinessException.forbidden("只有任课教师、班级导员或管理员可以登记该班考勤");
        }
    }

    private List<SysUser> classStudents(long classId) {
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getOrgUnitId, classId)
                .eq(SysUser::getStatus, UserStatus.ACTIVE)
                .orderByAsc(SysUser::getUsername));
    }

    /** 当天与该课程重叠的已通过请假：学生 → leaveId。 */
    private Map<Long, Long> approvedLeaves(List<Long> studentIds, long courseId, LocalDate date) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.atTime(LocalTime.MAX);
        List<LeaveRequest> leaves = leaveMapper.selectList(new LambdaQueryWrapper<LeaveRequest>()
                .in(LeaveRequest::getStudentId, studentIds)
                .eq(LeaveRequest::getCourseId, courseId)
                .eq(LeaveRequest::getStatus, LeaveStatus.APPROVED)
                .le(LeaveRequest::getStartAt, dayEnd)
                .ge(LeaveRequest::getEndAt, dayStart));
        Map<Long, Long> result = new HashMap<>();
        leaves.forEach(l -> result.putIfAbsent(l.getStudentId(), l.getId()));
        return result;
    }

    private AttendanceSession requireSession(long id) {
        AttendanceSession session = sessionMapper.selectById(id);
        if (session == null) {
            throw BusinessException.notFound("考勤记录");
        }
        return session;
    }

    private SysOrgUnit requireClass(long classId) {
        SysOrgUnit clazz = orgMapper.selectById(classId);
        if (clazz == null || clazz.getType() != OrgType.CLASS) {
            throw BusinessException.notFound("班级");
        }
        return clazz;
    }

    private Semester resolveSemester(Long semesterId) {
        if (semesterId != null) {
            Semester semester = semesterMapper.selectById(semesterId);
            if (semester == null) {
                throw BusinessException.notFound("学期");
            }
            return semester;
        }
        LocalDate today = LocalDate.now(clock);
        List<Semester> active = semesterMapper.selectList(new LambdaQueryWrapper<Semester>()
                .eq(Semester::getStatus, SemesterStatus.ACTIVE)
                .le(Semester::getStartDate, today)
                .ge(Semester::getEndDate, today)
                .orderByDesc(Semester::getStartDate)
                .last("LIMIT 1"));
        if (!active.isEmpty()) {
            return active.get(0);
        }
        List<Semester> latest = semesterMapper.selectList(new LambdaQueryWrapper<Semester>()
                .orderByDesc(Semester::getStartDate)
                .last("LIMIT 1"));
        return latest.isEmpty() ? null : latest.get(0);
    }

    private Map<Long, List<String>> teacherNames(List<Long> courseIds) {
        Map<Long, List<String>> names = new HashMap<>();
        List<CourseTeacher> relations = teacherMapper.selectByCourses(courseIds);
        Set<Long> ids = new HashSet<>();
        relations.forEach(r -> ids.add(r.getTeacherId()));
        Map<Long, SysUser> users = new HashMap<>();
        if (!ids.isEmpty()) {
            userMapper.selectBatchIds(ids).forEach(u -> users.put(u.getId(), u));
        }
        for (CourseTeacher relation : relations) {
            SysUser teacher = users.get(relation.getTeacherId());
            if (teacher != null) {
                names.computeIfAbsent(relation.getCourseId(), k -> new ArrayList<>())
                        .add(teacher.getDisplayName());
            }
        }
        return names;
    }

    private AttendanceSessionView toSessionView(AttendanceSession session, Course course, String className,
                                                List<AttendanceRecord> records) {
        int present = 0;
        int absent = 0;
        int late = 0;
        int leave = 0;
        for (AttendanceRecord record : records) {
            switch (record.getStatus()) {
                case PRESENT -> present++;
                case ABSENT -> absent++;
                case LATE -> late++;
                case LEAVE -> leave++;
            }
        }
        return new AttendanceSessionView(session.getId(), session.getCourseId(),
                course == null ? null : course.getCode(), course == null ? null : course.getName(),
                session.getClassId(), className, session.getSessionDate(),
                value(session.getStartPeriod()), value(session.getEndPeriod()), session.getStatus(),
                session.getNote(), present, absent, late, leave, session.getUpdatedAt());
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("ATTENDANCE_SESSION", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
