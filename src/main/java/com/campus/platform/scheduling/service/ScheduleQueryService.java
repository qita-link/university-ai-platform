package com.campus.platform.scheduling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.entity.Semester;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.scheduling.dto.ScheduleDtos;
import com.campus.platform.scheduling.dto.TermDtos;
import com.campus.platform.scheduling.entity.SchAdjustmentLog;
import com.campus.platform.scheduling.entity.SchAssignment;
import com.campus.platform.scheduling.entity.SchCampus;
import com.campus.platform.scheduling.entity.SchCourse;
import com.campus.platform.scheduling.entity.SchPeriod;
import com.campus.platform.scheduling.entity.SchRoom;
import com.campus.platform.scheduling.entity.SchTask;
import com.campus.platform.scheduling.entity.SchTaskTeacher;
import com.campus.platform.scheduling.entity.SchTeachingClass;
import com.campus.platform.scheduling.entity.SchTeachingClassMember;
import com.campus.platform.scheduling.entity.SchVersion;
import com.campus.platform.scheduling.entity.SchVersionIssue;
import com.campus.platform.scheduling.entity.VersionStatus;
import com.campus.platform.scheduling.mapper.SchAdjustmentLogMapper;
import com.campus.platform.scheduling.mapper.SchAssignmentMapper;
import com.campus.platform.scheduling.mapper.SchCampusMapper;
import com.campus.platform.scheduling.mapper.SchCourseMapper;
import com.campus.platform.scheduling.mapper.SchRoomMapper;
import com.campus.platform.scheduling.mapper.SchTaskMapper;
import com.campus.platform.scheduling.mapper.SchTaskTeacherMapper;
import com.campus.platform.scheduling.mapper.SchTeachingClassMapper;
import com.campus.platform.scheduling.mapper.SchTeachingClassMemberMapper;
import com.campus.platform.scheduling.mapper.SchVersionIssueMapper;
import com.campus.platform.scheduling.mapper.SchVersionMapper;
import com.campus.platform.scheduling.solver.SchedulingEvaluator;
import com.campus.platform.scheduling.solver.SchedulingValidator;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.Weeks;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 课表查询、版本管理、对比、导出。
 * <p>
 * 已发布版本不可直接修改：调整会创建新的草稿版本（见 {@link ScheduleAdjustService}）；
 * 重新求解也会生成新版本，不会覆盖已发布课表。
 */
@Service
public class ScheduleQueryService {

    static final String MODULE = "scheduling";

    private final SchVersionMapper versionMapper;
    private final SchAssignmentMapper assignmentMapper;
    private final SchVersionIssueMapper issueMapper;
    private final SchTaskMapper taskMapper;
    private final SchTaskTeacherMapper taskTeacherMapper;
    private final SchTeachingClassMapper teachingClassMapper;
    private final SchTeachingClassMemberMapper memberMapper;
    private final SchCourseMapper courseMapper;
    private final SchRoomMapper roomMapper;
    private final SchCampusMapper campusMapper;
    private final SchAdjustmentLogMapper adjustmentMapper;
    private final SemesterMapper semesterMapper;
    private final SysUserMapper userMapper;
    private final SysOrgUnitMapper orgUnitMapper;
    private final TermCalendarService termService;
    private final SchedulingProblemLoader loader;
    private final SchedulingValidator validator;
    private final SchedulingEvaluator evaluator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ScheduleQueryService(SchVersionMapper versionMapper, SchAssignmentMapper assignmentMapper,
                               SchVersionIssueMapper issueMapper, SchTaskMapper taskMapper,
                               SchTaskTeacherMapper taskTeacherMapper,
                               SchTeachingClassMapper teachingClassMapper,
                               SchTeachingClassMemberMapper memberMapper, SchCourseMapper courseMapper,
                               SchRoomMapper roomMapper, SchCampusMapper campusMapper,
                               SchAdjustmentLogMapper adjustmentMapper, SemesterMapper semesterMapper,
                               SysUserMapper userMapper, SysOrgUnitMapper orgUnitMapper,
                               TermCalendarService termService, SchedulingProblemLoader loader,
                               SchedulingValidator validator, SchedulingEvaluator evaluator,
                               AuditService auditService, ObjectMapper objectMapper) {
        this.versionMapper = versionMapper;
        this.assignmentMapper = assignmentMapper;
        this.issueMapper = issueMapper;
        this.taskMapper = taskMapper;
        this.taskTeacherMapper = taskTeacherMapper;
        this.teachingClassMapper = teachingClassMapper;
        this.memberMapper = memberMapper;
        this.courseMapper = courseMapper;
        this.roomMapper = roomMapper;
        this.campusMapper = campusMapper;
        this.adjustmentMapper = adjustmentMapper;
        this.semesterMapper = semesterMapper;
        this.userMapper = userMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.termService = termService;
        this.loader = loader;
        this.validator = validator;
        this.evaluator = evaluator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 版本

    public List<ScheduleDtos.VersionView> listVersions(AuthenticatedUser user, Long semesterId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        List<SchVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<SchVersion>()
                .eq(semesterId != null, SchVersion::getSemesterId, semesterId)
                .orderByDesc(SchVersion::getSemesterId).orderByDesc(SchVersion::getVersionNo));
        Map<Long, String> semesters = new LinkedHashMap<>();
        return versions.stream().map(v -> view(v, semesters.computeIfAbsent(v.getSemesterId(),
                this::semesterName))).toList();
    }

    public ScheduleDtos.VersionView getVersion(AuthenticatedUser user, long versionId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        SchVersion version = requireVersion(versionId);
        return view(version, semesterName(version.getSemesterId()));
    }

    public SchVersion requireVersion(long versionId) {
        SchVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw BusinessException.notFound("排课版本");
        }
        return version;
    }

    /** 复制版本为新草稿（不改动原版本，包括已发布版本）。 */
    @Transactional
    public ScheduleDtos.VersionView copy(AuthenticatedUser user, long versionId, ScheduleDtos.CopyRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchVersion source = requireVersion(versionId);
        Integer maxNo = versionMapper.selectList(new LambdaQueryWrapper<SchVersion>()
                        .eq(SchVersion::getSemesterId, source.getSemesterId())).stream()
                .map(SchVersion::getVersionNo).max(Integer::compareTo).orElse(0);
        SchVersion copy = new SchVersion();
        copy.setSemesterId(source.getSemesterId());
        copy.setVersionNo(maxNo + 1);
        copy.setName(req != null && req.name() != null && !req.name().isBlank() ? req.name().trim()
                : source.getName() + " 副本");
        copy.setSource("COPY");
        copy.setBaseVersionId(source.getId());
        copy.setStatus(VersionStatus.DRAFT);
        copy.setTotalSessions(source.getTotalSessions());
        copy.setPlacedSessions(source.getPlacedSessions());
        copy.setHardViolations(source.getHardViolations());
        copy.setScore(source.getScore());
        copy.setSoftPenalty(source.getSoftPenalty());
        copy.setEvaluationJson(source.getEvaluationJson());
        copy.setNote(req == null ? null : req.reason());
        copy.setVersion(0);
        copy.setCreatedBy(user.userId());
        copy.setUpdatedBy(user.userId());
        versionMapper.insert(copy);

        for (SchAssignment a : assignments(versionId)) {
            SchAssignment row = new SchAssignment();
            row.setVersionId(copy.getId());
            row.setTaskId(a.getTaskId());
            row.setSessionIndex(a.getSessionIndex());
            row.setDayOfWeek(a.getDayOfWeek());
            row.setStartPeriod(a.getStartPeriod());
            row.setEndPeriod(a.getEndPeriod());
            row.setRoomId(a.getRoomId());
            row.setLocked(a.getLocked());
            row.setOrigin("COPY");
            assignmentMapper.insert(row);
        }
        audit(user, "SCHEDULING_VERSION_COPY", copy.getId(), Map.of("from", versionId));
        return view(copy, semesterName(copy.getSemesterId()));
    }

    // ------------------------------------------------------------------ 课表

    /**
     * 课表筛选条件。
     */
    public record EntryFilter(Long teacherId, Long classId, Long roomId, Long courseId, Integer dayOfWeek,
                              Integer period, Integer week, boolean onlyPlaced) {
        public static EntryFilter none() {
            return new EntryFilter(null, null, null, null, null, null, null, false);
        }
    }

    public List<ScheduleDtos.EntryView> entries(AuthenticatedUser user, long versionId, EntryFilter filter) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        requireVersion(versionId);
        return entriesInternal(versionId, filter == null ? EntryFilter.none() : filter);
    }

    public List<ScheduleDtos.EntryView> entriesInternal(long versionId, EntryFilter filter) {
        List<SchAssignment> rows = assignments(versionId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Context ctx = context(versionId, rows);
        return rows.stream()
                .filter(a -> filter.onlyPlaced() ? a.getDayOfWeek() != null : true)
                .filter(a -> filter.dayOfWeek() == null || Objects.equals(a.getDayOfWeek(), filter.dayOfWeek()))
                .filter(a -> filter.roomId() == null || Objects.equals(a.getRoomId(), filter.roomId()))
                .filter(a -> filter.week == null || ctx.weeks(a.getTaskId()).contains(filter.week))
                .filter(a -> filter.period == null || (a.getStartPeriod() != null
                        && a.getStartPeriod() <= filter.period && filter.period <= a.getEndPeriod()))
                .filter(a -> filter.teacherId() == null
                        || ctx.teacherIds(a.getTaskId()).contains(filter.teacherId()))
                .filter(a -> filter.classId() == null || ctx.classIds(a.getTaskId()).contains(filter.classId()))
                .filter(a -> filter.courseId() == null
                        || Objects.equals(ctx.task(a.getTaskId()) == null ? null
                        : ctx.task(a.getTaskId()).getCourseId(), filter.courseId()))
                .sorted(Comparator.comparing((SchAssignment a) -> a.getDayOfWeek() == null ? 9 : a.getDayOfWeek())
                        .thenComparing(a -> a.getStartPeriod() == null ? 99 : a.getStartPeriod())
                        .thenComparing(SchAssignment::getTaskId))
                .map(a -> ctx.entry(a))
                .toList();
    }

    /** 按星期 × 节次组织的课表视图。 */
    public ScheduleDtos.TimetableView timetable(AuthenticatedUser user, long versionId, Long teacherId,
                                                Long classId, Long roomId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        SchVersion version = requireVersion(versionId);
        EntryFilter filter = new EntryFilter(teacherId, classId, roomId, null, null, null, null, true);
        List<ScheduleDtos.EntryView> entries = entriesInternal(versionId, filter);
        String scope = teacherId != null ? "TEACHER" : classId != null ? "CLASS" : roomId != null ? "ROOM" : "ALL";
        String owner = teacherId != null ? nameOfUser(teacherId)
                : classId != null ? nameOfClass(classId)
                : roomId != null ? roomCode(roomId) : "全部";
        return grid(version, entries, scope, owner);
    }

    /**
     * 本人课表：教师看自己，学生看自己所在行政班；只返回已发布版本。
     * <p>
     * 不传 semesterId 时按「当前学期已发布 → 最近一次发布」兜底：
     * 学期状态是 DRAFT、或当前学期还没发布时，教师/学生仍然应该能看到最近一份课表，
     * 而不是一个 404。
     */
    public ScheduleDtos.TimetableView myTimetable(AuthenticatedUser user, Long semesterId) {
        Permissions.require(user, Permissions.TIMETABLE_READ);
        SchVersion published = publishedVersion(semesterId);
        boolean teacher = user.isTeacher();
        Long teacherId = teacher ? user.userId() : null;
        Long classId = null;
        if (!teacher && user.orgUnitId() != null) {
            SysOrgUnit org = orgUnitMapper.selectById(user.orgUnitId());
            if (org != null && org.getType() == OrgType.CLASS) {
                classId = org.getId();
            }
        }
        if (teacherId == null && classId == null) {
            throw BusinessException.rule("当前账号没有关联教师或行政班，无法查看个人课表");
        }
        List<ScheduleDtos.EntryView> entries = entriesInternal(published.getId(),
                new EntryFilter(teacherId, classId, null, null, null, null, null, true));
        return grid(published, entries, teacherId != null ? "TEACHER" : "CLASS",
                teacherId != null ? user.displayName() : nameOfClass(classId));
    }

    /**
     * 教室课表：按教室查看已发布课表的占用情况（这一间教室一周里上了哪些课）。
     * <p>
     * 教师没有 {@code schedule:read}（那是排课工作台的权限），因此这里接受
     * {@code schedule:preference:self}——教师与管理员都有、学生没有，正好是「按教室查课」的可见范围。
     */
    public ScheduleDtos.TimetableView roomTimetable(AuthenticatedUser user, long roomId, Long semesterId) {
        Permissions.requireAny(user, Permissions.SCHEDULE_READ, Permissions.SCHEDULE_PREFERENCE_SELF);
        SchRoom room = roomMapper.selectById(roomId);
        if (room == null) {
            throw BusinessException.notFound("教室");
        }
        SchVersion published = publishedVersion(semesterId);
        List<ScheduleDtos.EntryView> entries = entriesInternal(published.getId(),
                new EntryFilter(null, null, roomId, null, null, null, null, true));
        String owner = room.getCode() == null ? String.valueOf(roomId)
                : room.getName() == null || room.getName().isBlank() || room.getName().equals(room.getCode())
                ? room.getCode() : room.getCode() + " · " + room.getName();
        return grid(published, entries, "ROOM", owner);
    }

    /**
     * 节次作息（给本人课表/课程页左侧的时间列用）。
     * <p>
     * 只要求 {@code timetable:read}：学生也要看到「第 1 节 08:00–08:45」，
     * 但他们没有 {@code schedule:read}，所以不能复用排课设置里的 /terms/{id}/periods。
     * 学期没配作息时返回空数组，由前端退化成只显示节次号。
     */
    public List<TermDtos.PeriodView> periods(AuthenticatedUser user, long semesterId) {
        Permissions.require(user, Permissions.TIMETABLE_READ);
        return termService.periodViewsWithFallback(semesterId);
    }

    /**
     * 已发布版本：指定学期取该学期的；不指定时按「当前学期已发布 → 最近一次发布」兜底。
     */
    private SchVersion publishedVersion(Long semesterId) {
        SchVersion published = null;
        if (semesterId != null) {
            published = publishedOf(semesterId);
        } else {
            Semester current = currentSemester();
            if (current != null) {
                published = publishedOf(current.getId());
            }
            if (published == null) {
                published = versionMapper.selectOne(new LambdaQueryWrapper<SchVersion>()
                        .eq(SchVersion::getStatus, VersionStatus.PUBLISHED)
                        .orderByDesc(SchVersion::getPublishedAt)
                        .last("LIMIT 1"));
            }
        }
        if (published == null) {
            throw BusinessException.notFound("已发布课表");
        }
        return published;
    }

    private SchVersion publishedOf(long semesterId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<SchVersion>()
                .eq(SchVersion::getSemesterId, semesterId)
                .eq(SchVersion::getStatus, VersionStatus.PUBLISHED)
                .last("LIMIT 1"));
    }

    private ScheduleDtos.TimetableView grid(SchVersion version, List<ScheduleDtos.EntryView> entries,
                                            String scope, String owner) {
        List<SchPeriod> periods = termService.periodRows(version.getSemesterId());
        List<Integer> days = TermCalendarService.parseDays(
                termService.requireConfig(version.getSemesterId()).getTeachingDays());
        List<ScheduleDtos.TimetableRow> rows = new ArrayList<>();
        for (SchPeriod p : periods) {
            List<ScheduleDtos.TimetableCell> cells = new ArrayList<>();
            for (Integer day : days) {
                List<ScheduleDtos.EntryView> here = entries.stream()
                        .filter(e -> Objects.equals(e.dayOfWeek(), day))
                        .filter(e -> e.startPeriod() != null && e.endPeriod() != null
                                && e.startPeriod() <= p.getPeriodNo() && p.getPeriodNo() <= e.endPeriod())
                        .toList();
                cells.add(new ScheduleDtos.TimetableCell(day, here));
            }
            rows.add(new ScheduleDtos.TimetableRow(p.getPeriodNo(), p.getStartTime(), p.getEndTime(), cells));
        }
        return new ScheduleDtos.TimetableView(version.getId(), version.getName(), scope, owner, days, rows);
    }

    // ------------------------------------------------------------------ 未安排 / 评分 / 冲突

    public List<ScheduleDtos.UnplacedView> unplaced(AuthenticatedUser user, long versionId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        requireVersion(versionId);
        List<SchAssignment> rows = assignments(versionId);
        Context ctx = context(versionId, rows);
        Map<Long, List<SchAssignment>> byTask = rows.stream()
                .collect(Collectors.groupingBy(SchAssignment::getTaskId, LinkedHashMap::new, Collectors.toList()));
        List<SchVersionIssue> issues = issueMapper.selectList(new LambdaQueryWrapper<SchVersionIssue>()
                .eq(SchVersionIssue::getVersionId, versionId).eq(SchVersionIssue::getCode, "UNSCHEDULED"));
        Map<Long, String> reasons = new LinkedHashMap<>();
        for (SchVersionIssue issue : issues) {
            if (issue.getTaskId() != null) {
                reasons.put(issue.getTaskId(), issue.getMessage());
            }
        }
        List<ScheduleDtos.UnplacedView> out = new ArrayList<>();
        byTask.forEach((taskId, list) -> {
            int placed = (int) list.stream().filter(a -> a.getDayOfWeek() != null).count();
            SchTask task = ctx.task(taskId);
            if (task == null || placed >= task.getSessionsPerWeek()) {
                return;
            }
            out.add(new ScheduleDtos.UnplacedView(taskId, task.getCode(),
                    ctx.courseName(task.getCourseId()), ctx.teachingClassName(task.getTeachingClassId()),
                    task.getSessionsPerWeek(), placed, task.getSessionsPerWeek() - placed,
                    reasons.getOrDefault(taskId, "求解器未能在约束内安排该课次")));
        });
        return out;
    }

    /** 实时复核 + 评分（不落库；如需固化请调用 POST /versions/{id}/check）。 */
    public ScheduleDtos.EvaluationView evaluation(AuthenticatedUser user, long versionId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        return evaluate(requireVersion(versionId));
    }

    public ScheduleDtos.EvaluationView evaluate(SchVersion version) {
        List<SchAssignment> rows = assignments(version.getId());
        List<Long> taskIds = rows.stream().map(SchAssignment::getTaskId).distinct().toList();
        if (taskIds.isEmpty()) {
            return new ScheduleDtos.EvaluationView(version.getScore() == null ? 0
                    : version.getScore().doubleValue(), 0, version.getTotalSessions() == null ? 0
                    : version.getTotalSessions(), 0, 0, List.of(), List.of());
        }
        SchedulingProblemLoader.Loaded loaded;
        try {
            loaded = loader.load(new SchedulingProblemLoader.LoadRequest(version.getSemesterId(), taskIds, null,
                    null, true, false, null));
        } catch (BusinessException ex) {
            return new ScheduleDtos.EvaluationView(version.getScore() == null ? 0
                    : version.getScore().doubleValue(), version.getPlacedSessions() == null ? 0
                    : version.getPlacedSessions(), version.getTotalSessions() == null ? 0
                    : version.getTotalSessions(), version.getHardViolations() == null ? 0
                    : version.getHardViolations(), version.getSoftPenalty() == null ? 0
                    : version.getSoftPenalty(), List.of(),
                    List.of(new ScheduleDtos.ConflictView("DATA_INVALID", "数据不完整", "ERROR", null, null,
                            null, null, null, null, List.of(), List.of(), List.of(), ex.getMessage(),
                            "补齐基础数据后重新复核")));
        }
        List<Placement> placements = rows.stream()
                .map(a -> new Placement(a.getTaskId(), a.getSessionIndex(), a.getDayOfWeek(), a.getStartPeriod(),
                        a.getRoomId(), Boolean.TRUE.equals(a.getLocked())))
                .toList();
        SchedulingProblem problem = loaded.problem();
        SchedulingValidator.Report report = validator.validate(problem, placements);
        SchedulingEvaluator.Report evaluation = evaluator.evaluate(problem, placements, report);
        return new ScheduleDtos.EvaluationView(evaluation.score(), evaluation.placedSessions(),
                evaluation.totalSessions(), evaluation.hardViolations(), evaluation.softPenalty(),
                evaluation.details().stream().map(d -> new ScheduleDtos.SoftScoreView(d.code().name(),
                        d.label(), d.weight(), d.applicable(), d.violated(), d.penalty(), d.satisfaction()))
                        .toList(),
                ScheduleViewSupport.conflicts(report, loaded));
    }

    public List<ScheduleDtos.ConflictView> conflicts(AuthenticatedUser user, long versionId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        return evaluation(user, versionId).conflicts();
    }

    // ------------------------------------------------------------------ 对比

    public ScheduleDtos.CompareView compare(AuthenticatedUser user, long versionA, long versionB) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        SchVersion a = requireVersion(versionA);
        SchVersion b = requireVersion(versionB);
        Map<String, SchAssignment> mapA = byKey(assignments(versionA));
        Map<String, SchAssignment> mapB = byKey(assignments(versionB));
        Context ctx = context(versionA, assignments(versionA));
        Map<String, String> courseNames = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(mapA.keySet());
        keys.addAll(mapB.keySet());
        int added = 0;
        int removed = 0;
        int moved = 0;
        int unchanged = 0;
        List<ScheduleDtos.EntryDiff> diffs = new ArrayList<>();
        for (String key : keys) {
            SchAssignment x = mapA.get(key);
            SchAssignment y = mapB.get(key);
            long taskIdOfEntry = x != null ? x.getTaskId() : y.getTaskId();
            var taskOfEntry = ctx.task(taskIdOfEntry);
            String taskCode = taskOfEntry == null ? null : taskOfEntry.getCode();
            String course = taskOfEntry == null ? null : ctx.courseName(taskOfEntry.getCourseId());
            courseNames.put(key, course);
            String from = x == null ? "—" : ScheduleViewSupport.timeText(x.getDayOfWeek(), x.getStartPeriod(),
                    x.getEndPeriod()) + " " + roomLabel(x.getRoomId());
            String to = y == null ? "—" : ScheduleViewSupport.timeText(y.getDayOfWeek(), y.getStartPeriod(),
                    y.getEndPeriod()) + " " + roomLabel(y.getRoomId());
            if (x != null && y != null && Objects.equals(x.getDayOfWeek(), y.getDayOfWeek())
                    && Objects.equals(x.getStartPeriod(), y.getStartPeriod())
                    && Objects.equals(x.getRoomId(), y.getRoomId())) {
                unchanged++;
                continue;
            }
            String change;
            if (x == null) {
                added++;
                change = "新增";
            } else if (y == null) {
                removed++;
                change = "移除";
            } else {
                moved++;
                change = Objects.equals(x.getDayOfWeek(), y.getDayOfWeek())
                        && Objects.equals(x.getStartPeriod(), y.getStartPeriod()) ? "换教室" : "改时间";
            }
            diffs.add(new ScheduleDtos.EntryDiff(x != null ? x.getTaskId() : y.getTaskId(), taskCode, course,
                    x != null ? x.getSessionIndex() : y.getSessionIndex(), from, to, change));
        }
        return new ScheduleDtos.CompareView(a.getId(), b.getId(), a.getName(), b.getName(), added, removed,
                moved, unchanged, diffs);
    }

    private Map<String, SchAssignment> byKey(List<SchAssignment> rows) {
        Map<String, SchAssignment> map = new LinkedHashMap<>();
        rows.forEach(a -> map.put(a.getTaskId() + "#" + a.getSessionIndex(), a));
        return map;
    }

    // ------------------------------------------------------------------ 调整历史

    public List<ScheduleDtos.AdjustmentView> adjustments(AuthenticatedUser user, long versionId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        requireVersion(versionId);
        return adjustmentMapper.selectList(new LambdaQueryWrapper<SchAdjustmentLog>()
                        .eq(SchAdjustmentLog::getVersionId, versionId)
                        .orderByDesc(SchAdjustmentLog::getId)).stream()
                .map(l -> new ScheduleDtos.AdjustmentView(l.getId(), l.getVersionId(), l.getAssignmentId(),
                        l.getAction(),
                        ScheduleViewSupport.timeText(l.getFromDay(), l.getFromPeriod(), l.getFromPeriod())
                                + " " + roomLabel(l.getFromRoomId()),
                        ScheduleViewSupport.timeText(l.getToDay(), l.getToPeriod(), l.getToPeriod())
                                + " " + roomLabel(l.getToRoomId()),
                        l.getReason(), l.getCreatedBy(), l.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ 导出

    /** 导出 CSV（UTF-8 BOM，Excel 直接打开不乱码）。 */
    public byte[] exportCsv(AuthenticatedUser user, long versionId, Long teacherId, Long classId, Long roomId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        SchVersion version = requireVersion(versionId);
        List<ScheduleDtos.EntryView> entries = entriesInternal(versionId,
                new EntryFilter(teacherId, classId, roomId, null, null, null, null, true));
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("学期,版本,课程,教学任务,教师,班级,教室,星期,开始节,结束节,周次\n");
        String semester = semesterName(version.getSemesterId());
        for (ScheduleDtos.EntryView e : entries) {
            sb.append(csv(semester)).append(',')
                    .append(csv(version.getName())).append(',')
                    .append(csv(e.courseName())).append(',')
                    .append(csv(e.taskCode())).append(',')
                    .append(csv(String.join("/", e.teacherNames()))).append(',')
                    .append(csv(String.join("/", e.classNames()))).append(',')
                    .append(csv(e.roomCode())).append(',')
                    .append(TaskServiceSupport.dayName(e.dayOfWeek())).append(',')
                    .append(e.startPeriod()).append(',')
                    .append(e.endPeriod()).append(',')
                    .append(csv(e.weekText())).append('\n');
        }
        audit(user, "SCHEDULING_EXPORT", versionId, Map.of("rows", entries.size()));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 上下文

    private record Context(Map<Long, SchTask> tasks, Map<Long, SchCourse> courses,
                           Map<Long, SchTeachingClass> teachingClasses,
                           Map<Long, List<SchTeachingClassMember>> members,
                           Map<Long, SysOrgUnit> orgs,
                           Map<Long, List<Long>> taskTeachers, Map<Long, SysUser> teachers,
                           Map<Long, SchRoom> rooms, Map<Long, String> campuses,
                           Map<Long, List<Integer>> weeks) {

        SchTask task(Long id) {
            return tasks.get(id);
        }

        String courseName(Long courseId) {
            SchCourse c = courses.get(courseId);
            return c == null ? null : c.getName();
        }

        String teachingClassName(Long id) {
            SchTeachingClass tc = teachingClasses.get(id);
            return tc == null ? null : tc.getName();
        }

        List<Long> teacherIds(Long taskId) {
            return taskTeachers.getOrDefault(taskId, List.of());
        }

        List<Long> classIds(Long taskId) {
            SchTask t = tasks.get(taskId);
            if (t == null) {
                return List.of();
            }
            return members.getOrDefault(t.getTeachingClassId(), List.of()).stream()
                    .map(SchTeachingClassMember::getOrgUnitId).toList();
        }

        List<Integer> weeks(Long taskId) {
            return weeks.getOrDefault(taskId, List.of());
        }

        ScheduleDtos.EntryView entry(SchAssignment a) {
            SchTask t = tasks.get(a.getTaskId());
            List<String> teacherNames = teacherIds(a.getTaskId()).stream()
                    .map(id -> teachers.containsKey(id) ? teachers.get(id).getDisplayName() : String.valueOf(id))
                    .toList();
            List<String> classNames = classIds(a.getTaskId()).stream()
                    .map(id -> orgs.containsKey(id) ? orgs.get(id).getName() : String.valueOf(id)).toList();
            SchRoom room = a.getRoomId() == null ? null : rooms.get(a.getRoomId());
            return new ScheduleDtos.EntryView(a.getId(), a.getTaskId(), t == null ? null : t.getCode(),
                    t == null ? null : courseName(t.getCourseId()),
                    t == null ? null : teachingClassName(t.getTeachingClassId()), classNames, teacherNames,
                    a.getRoomId(), room == null ? null : room.getCode(),
                    room == null ? null : campuses.get(room.getCampusId()),
                    a.getDayOfWeek(), a.getStartPeriod(), a.getEndPeriod(), a.getLocked(), a.getOrigin(),
                    weeks(a.getTaskId()), weekText(a.getTaskId()),
                    ScheduleViewSupport.timeText(a.getDayOfWeek(), a.getStartPeriod(), a.getEndPeriod()));
        }

        String weekText(Long taskId) {
            List<Integer> list = weeks(taskId);
            if (list.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < list.size()) {
                int s = list.get(i);
                int e = s;
                while (i + 1 < list.size() && list.get(i + 1) == e + 1) {
                    e = list.get(++i);
                }
                if (!sb.isEmpty()) {
                    sb.append(',');
                }
                sb.append(s == e ? String.valueOf(s) : s + "-" + e);
                i++;
            }
            return sb.append("周").toString();
        }
    }

    private Context context(long versionId, List<SchAssignment> rows) {
        List<Long> taskIds = rows.stream().map(SchAssignment::getTaskId).distinct().toList();
        Map<Long, SchTask> tasks = taskIds.isEmpty() ? Map.of()
                : taskMapper.selectBatchIds(taskIds).stream()
                .collect(Collectors.toMap(SchTask::getId, t -> t, (a, b) -> a));
        Map<Long, SchCourse> courses = tasks.isEmpty() ? Map.of()
                : courseMapper.selectBatchIds(tasks.values().stream().map(SchTask::getCourseId).distinct()
                .toList()).stream().collect(Collectors.toMap(SchCourse::getId, c -> c, (a, b) -> a));
        Map<Long, SchTeachingClass> tcs = tasks.isEmpty() ? Map.of()
                : teachingClassMapper.selectBatchIds(tasks.values().stream()
                .map(SchTask::getTeachingClassId).distinct().toList()).stream()
                .collect(Collectors.toMap(SchTeachingClass::getId, t -> t, (a, b) -> a));
        Map<Long, List<SchTeachingClassMember>> members = new LinkedHashMap<>();
        if (!tcs.isEmpty()) {
            memberMapper.selectList(new LambdaQueryWrapper<SchTeachingClassMember>()
                            .in(SchTeachingClassMember::getTeachingClassId, tcs.keySet()))
                    .forEach(m -> members.computeIfAbsent(m.getTeachingClassId(), k -> new ArrayList<>()).add(m));
        }
        List<Long> classIds = members.values().stream().flatMap(List::stream)
                .map(SchTeachingClassMember::getOrgUnitId).distinct().toList();
        Map<Long, SysOrgUnit> orgs = classIds.isEmpty() ? Map.of()
                : orgUnitMapper.selectBatchIds(classIds).stream()
                .collect(Collectors.toMap(SysOrgUnit::getId, o -> o, (a, b) -> a));
        Map<Long, List<Long>> taskTeachers = new LinkedHashMap<>();
        if (!tasks.isEmpty()) {
            taskTeacherMapper.selectList(new LambdaQueryWrapper<SchTaskTeacher>()
                            .in(SchTaskTeacher::getTaskId, tasks.keySet()))
                    .forEach(r -> taskTeachers.computeIfAbsent(r.getTaskId(), k -> new ArrayList<>())
                            .add(r.getTeacherId()));
        }
        Set<Long> teacherIds = taskTeachers.values().stream().flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SysUser> teachers = teacherIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(teacherIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        Map<Long, SchRoom> rooms = roomMapper.selectList(null).stream()
                .collect(Collectors.toMap(SchRoom::getId, r -> r, (a, b) -> a));
        Map<Long, String> campuses = campusMapper.selectList(null).stream()
                .collect(Collectors.toMap(SchCampus::getId, SchCampus::getName, (a, b) -> a));
        // 同一版本的任务属于同一学期，日历只查一次（原先每个任务查 4 次，几十个任务就是上百次 SQL）
        Map<Long, SchedulingProblemLoader.CalendarContext> calendars = new LinkedHashMap<>();
        Map<Long, List<Integer>> weeks = new LinkedHashMap<>();
        tasks.values().forEach(t -> {
            List<Integer> list = new ArrayList<>();
            SchedulingProblemLoader.CalendarContext ctx = calendars.get(t.getSemesterId());
            if (ctx == null && !calendars.containsKey(t.getSemesterId())) {
                try {
                    ctx = loader.calendar(t.getSemesterId());
                } catch (RuntimeException ex) {
                    ctx = null;
                }
                calendars.put(t.getSemesterId(), ctx);
            }
            if (ctx != null) {
                long mask = SchedulingProblemLoader.effectiveWeeks(ctx, t);
                for (int w = 1; w <= Weeks.MAX_WEEKS; w++) {
                    if (Weeks.has(mask, w)) {
                        list.add(w);
                    }
                }
            }
            weeks.put(t.getId(), list);
        });
        return new Context(tasks, courses, tcs, members, orgs, taskTeachers, teachers, rooms, campuses, weeks);
    }

    private List<SchAssignment> assignments(long versionId) {
        return assignmentMapper.selectList(new LambdaQueryWrapper<SchAssignment>()
                .eq(SchAssignment::getVersionId, versionId)
                .orderByAsc(SchAssignment::getTaskId).orderByAsc(SchAssignment::getSessionIndex));
    }

    private ScheduleDtos.VersionView view(SchVersion v, String semesterName) {
        return new ScheduleDtos.VersionView(v.getId(), v.getSemesterId(), semesterName, v.getVersionNo(),
                v.getName(), v.getSource(), v.getJobId(), v.getBaseVersionId(),
                v.getStatus() == null ? null : v.getStatus().name(), v.getTotalSessions(), v.getPlacedSessions(),
                v.getHardViolations(), v.getScore(), v.getSoftPenalty(), v.getPublishedAt(), v.getPublishedBy(),
                v.getCreatedAt(), v.getVersion(), v.getNote());
    }

    private Semester currentSemester() {
        List<Semester> actives = semesterMapper.selectList(new LambdaQueryWrapper<Semester>()
                .eq(Semester::getStatus, com.campus.platform.course.entity.SemesterStatus.ACTIVE)
                .orderByDesc(Semester::getStartDate));
        if (actives.isEmpty()) {
            return null;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        return actives.stream().filter(s -> s.covers(today)).findFirst().orElse(actives.get(0));
    }

    private String semesterName(long semesterId) {
        Semester semester = semesterMapper.selectById(semesterId);
        return semester == null ? String.valueOf(semesterId) : semester.getName();
    }

    private String nameOfUser(long userId) {
        SysUser user = userMapper.selectById(userId);
        return user == null ? String.valueOf(userId) : user.getDisplayName();
    }

    private String nameOfClass(long classId) {
        SysOrgUnit org = orgUnitMapper.selectById(classId);
        return org == null ? String.valueOf(classId) : org.getName();
    }

    private String roomLabel(Long roomId) {
        if (roomId == null) {
            return "";
        }
        SchRoom room = roomMapper.selectById(roomId);
        return room == null ? String.valueOf(roomId) : room.getCode();
    }

    private String roomCode(Long roomId) {
        return roomLabel(roomId);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    static String durationText(LocalDateTime from, LocalDateTime to) {
        if (from == null) {
            return "";
        }
        Duration d = Duration.between(from, to == null ? LocalDateTime.now() : to);
        return d.toMinutes() + " 分钟";
    }

    static boolean overlaps(Integer dayA, Integer startA, Integer endA, Integer dayB, Integer startB,
                            Integer endB) {
        return Objects.equals(dayA, dayB) && startA != null && endA != null && startB != null && endB != null
                && startA <= endB && startB <= endA;
    }

    static LocalTime minutesToTime(int minutes) {
        return LocalTime.of(minutes / 60, minutes % 60);
    }

    private void audit(AuthenticatedUser user, String action, Long targetId, Map<String, ?> detail) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("schedule_version", targetId);
        detail.forEach(b::detail);
        auditService.record(b.build());
    }
}
