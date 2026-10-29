package com.campus.platform.scheduling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.scheduling.dto.JobDtos;
import com.campus.platform.scheduling.dto.ScheduleDtos;
import com.campus.platform.scheduling.entity.SchAdjustmentLog;
import com.campus.platform.scheduling.entity.SchAssignment;
import com.campus.platform.scheduling.entity.SchCourse;
import com.campus.platform.scheduling.entity.SchRoom;
import com.campus.platform.scheduling.entity.SchTask;
import com.campus.platform.scheduling.entity.SchVersion;
import com.campus.platform.scheduling.entity.SchVersionIssue;
import com.campus.platform.scheduling.entity.VersionStatus;
import com.campus.platform.scheduling.mapper.SchAdjustmentLogMapper;
import com.campus.platform.scheduling.mapper.SchAssignmentMapper;
import com.campus.platform.scheduling.mapper.SchCourseMapper;
import com.campus.platform.scheduling.mapper.SchRoomMapper;
import com.campus.platform.scheduling.mapper.SchTaskMapper;
import com.campus.platform.scheduling.mapper.SchVersionIssueMapper;
import com.campus.platform.scheduling.mapper.SchVersionMapper;
import com.campus.platform.scheduling.solver.RoomMatcher;
import com.campus.platform.scheduling.solver.SchedulingEvaluator;
import com.campus.platform.scheduling.solver.SchedulingValidator;
import com.campus.platform.scheduling.solver.TaskRules;
import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.RoomDef;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 课表调整：单节课移动/取消安排、锁定/解锁、可选位置查询、局部重排、复核。
 * <p>
 * 约束：只有 DRAFT 版本可调整；已发布版本必须先复制出新版本。
 * 每次调整都会用独立复核器检查冲突，冲突时拒绝并返回具体原因，不会静默破坏课表。
 */
@Service
public class ScheduleAdjustService {

    static final String MODULE = "scheduling";

    private final SchVersionMapper versionMapper;
    private final SchAssignmentMapper assignmentMapper;
    private final SchAdjustmentLogMapper adjustmentMapper;
    private final SchVersionIssueMapper issueMapper;
    private final SchTaskMapper taskMapper;
    private final SchCourseMapper courseMapper;
    private final SchRoomMapper roomMapper;
    private final TermCalendarService termService;
    private final SchedulingProblemLoader loader;
    private final SchedulingValidator validator;
    private final SchedulingEvaluator evaluator;
    private final ScheduleQueryService queryService;
    private final SchedulingJobService jobService;
    private final AuditService auditService;

    public ScheduleAdjustService(SchVersionMapper versionMapper, SchAssignmentMapper assignmentMapper,
                                 SchAdjustmentLogMapper adjustmentMapper, SchVersionIssueMapper issueMapper,
                                 SchTaskMapper taskMapper, SchCourseMapper courseMapper,
                                 SchRoomMapper roomMapper, TermCalendarService termService,
                                 SchedulingProblemLoader loader, SchedulingValidator validator,
                                 SchedulingEvaluator evaluator, ScheduleQueryService queryService,
                                 SchedulingJobService jobService, AuditService auditService) {
        this.versionMapper = versionMapper;
        this.assignmentMapper = assignmentMapper;
        this.adjustmentMapper = adjustmentMapper;
        this.issueMapper = issueMapper;
        this.taskMapper = taskMapper;
        this.courseMapper = courseMapper;
        this.roomMapper = roomMapper;
        this.termService = termService;
        this.loader = loader;
        this.validator = validator;
        this.evaluator = evaluator;
        this.queryService = queryService;
        this.jobService = jobService;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ 移动 / 取消安排

    @Transactional
    public ScheduleDtos.EntryView adjust(AuthenticatedUser user, long versionId, long entryId,
                                         ScheduleDtos.AdjustRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_ADJUST);
        SchVersion version = editableVersion(versionId);
        SchAssignment assignment = requireAssignment(versionId, entryId);
        SchTask task = requireTask(assignment.getTaskId());
        if (Boolean.TRUE.equals(assignment.getLocked()) && !Boolean.TRUE.equals(req.force())) {
            throw BusinessException.conflict("该课时已锁定，如需调整请先解锁（或使用 force=true 强制调整）");
        }
        Integer fromDay = assignment.getDayOfWeek();
        Integer fromPeriod = assignment.getStartPeriod();
        Long fromRoom = assignment.getRoomId();

        if (req.dayOfWeek() == null) {
            unplace(assignment);
            log(user, versionId, assignment.getId(), "UNPLACE", fromDay, fromPeriod, fromRoom, null, null, null,
                    req.reason());
            refresh(user, version);
            audit(user, "SCHEDULING_ENTRY_UNPLACE", assignment.getId(), Map.of("versionId", versionId));
            return queryService.entriesInternal(versionId, ScheduleQueryService.EntryFilter.none()).stream()
                    .filter(e -> Objects.equals(e.id(), entryId)).findFirst().orElseThrow();
        }

        int start = req.startPeriod();
        int end = start + task.getPeriodsPerSession() - 1;
        SchedulingProblemLoader.Loaded loaded = loadProblem(version);
        SchedulingProblem problem = loaded.problem();
        var taskDef = problem.task(task.getId());
        if (taskDef == null) {
            throw BusinessException.rule("该课时所属教学任务不在当前排课范围内，无法校验调整");
        }
        if (!problem.teachingDays().contains(req.dayOfWeek())) {
            throw BusinessException.badRequest("目标日期不是教学日：" + TaskServiceSupport.dayName(req.dayOfWeek()));
        }
        if (!problem.validSpan(start, task.getPeriodsPerSession())) {
            throw BusinessException.badRequest("目标时段不合法：连续 " + task.getPeriodsPerSession()
                    + " 节必须连续且不能跨越午休");
        }
        TaskRules rules = TaskRules.of(problem, taskDef);
        ConstraintCode forbidden = rules.forbiddenBy(req.dayOfWeek(), start, end);
        if (forbidden != null) {
            throw BusinessException.conflict("目标时段被禁排规则覆盖（" + forbidden.label() + "）");
        }
        if (!rules.fixedOk(req.dayOfWeek(), start, end)) {
            throw BusinessException.conflict("该课程被固定在其他时段，不能移动到此处");
        }
        Long roomId = req.roomId() != null ? req.roomId() : assignment.getRoomId();
        if (Boolean.TRUE.equals(task.getRoomRequired()) && roomId == null) {
            throw BusinessException.badRequest("该教学任务需要教室，请指定 roomId");
        }
        if (roomId != null) {
            validateRoom(problem, taskDef, roomId, req.dayOfWeek(), start, end);
        }

        List<Placement> existing = placements(versionId).stream()
                .filter(p -> !Objects.equals(p.taskId(), assignment.getTaskId())
                        || !Objects.equals(p.sessionIndex(), assignment.getSessionIndex()))
                .toList();
        // 同一教学任务的其它课次也要检查：不能同时同地，默认也不能同一天两次
        for (Placement sibling : existing) {
            if (sibling.taskId() != assignment.getTaskId() || !sibling.placed()) {
                continue;
            }
            if (Objects.equals(sibling.day(), req.dayOfWeek()) && sibling.startPeriod() == start) {
                throw BusinessException.conflict("同一教学任务的第 " + sibling.sessionIndex()
                        + " 次课已占用 " + TaskServiceSupport.slotText(start, start, end) + "，不能重复安排");
            }
            if (!Boolean.TRUE.equals(task.getAllowSameDay()) && Objects.equals(sibling.day(), req.dayOfWeek())) {
                throw BusinessException.conflict("该教学任务不允许同一天安排两次课（第 "
                        + sibling.sessionIndex() + " 次课已在" + TaskServiceSupport.dayName(req.dayOfWeek()) + "）");
            }
        }
        Placement candidate = new Placement(assignment.getTaskId(), assignment.getSessionIndex(),
                req.dayOfWeek(), start, roomId, false);
        List<Placement> conflicts = validator.conflictsOf(problem, existing, candidate);
        if (!conflicts.isEmpty()) {
            String detail = conflicts.stream().limit(3).map(c -> {
                SchTask other = taskMapper.selectById(c.taskId());
                return (other == null ? String.valueOf(c.taskId()) : other.getCode()) + " "
                        + ScheduleViewSupport.timeText(c.day(), c.startPeriod(), c.startPeriod());
            }).reduce((a, b) -> a + "；" + b).orElse("");
            throw BusinessException.conflict("目标位置与已有课程冲突：" + detail);
        }

        assignment.setDayOfWeek(req.dayOfWeek());
        assignment.setStartPeriod(start);
        assignment.setEndPeriod(end);
        assignment.setRoomId(roomId);
        assignment.setOrigin("MANUAL");
        // 保留原锁定状态：强制移动已锁定课时后它仍然是锁定的
        assignmentMapper.updateById(assignment);
        log(user, versionId, assignment.getId(), "MOVE", fromDay, fromPeriod, fromRoom, req.dayOfWeek(), start,
                roomId, req.reason());
        refresh(user, version);
        audit(user, "SCHEDULING_ENTRY_MOVE", assignment.getId(), Map.of("versionId", versionId,
                "to", TaskServiceSupport.slotText(req.dayOfWeek(), start, end)));
        return queryService.entriesInternal(versionId, ScheduleQueryService.EntryFilter.none()).stream()
                .filter(e -> Objects.equals(e.id(), entryId)).findFirst().orElseThrow();
    }

    private void unplace(SchAssignment assignment) {
        assignment.setDayOfWeek(null);
        assignment.setStartPeriod(null);
        assignment.setEndPeriod(null);
        assignment.setRoomId(null);
        assignment.setLocked(false);
        assignment.setOrigin("MANUAL");
        assignmentMapper.updateById(assignment);
    }

    @Transactional
    public ScheduleDtos.EntryView lock(AuthenticatedUser user, long versionId, long entryId,
                                       ScheduleDtos.LockRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_ADJUST);
        editableVersion(versionId);
        SchAssignment assignment = requireAssignment(versionId, entryId);
        if (Boolean.TRUE.equals(req.locked()) && assignment.getDayOfWeek() == null) {
            throw BusinessException.rule("未安排的课次不能锁定，请先安排时间");
        }
        assignment.setLocked(req.locked());
        assignmentMapper.updateById(assignment);
        log(user, versionId, assignment.getId(), Boolean.TRUE.equals(req.locked()) ? "LOCK" : "UNLOCK",
                assignment.getDayOfWeek(), assignment.getStartPeriod(), assignment.getRoomId(),
                assignment.getDayOfWeek(), assignment.getStartPeriod(), assignment.getRoomId(), req.reason());
        audit(user, Boolean.TRUE.equals(req.locked()) ? "SCHEDULING_ENTRY_LOCK" : "SCHEDULING_ENTRY_UNLOCK",
                assignment.getId(), Map.of("versionId", versionId));
        return queryService.entriesInternal(versionId, ScheduleQueryService.EntryFilter.none()).stream()
                .filter(e -> Objects.equals(e.id(), entryId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ 可选位置

    /** 某节课的候选位置（已排除与现有课表冲突的组合）。 */
    public List<ScheduleDtos.CandidateView> candidates(AuthenticatedUser user, long versionId, long entryId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        SchVersion version = queryService.requireVersion(versionId);
        SchAssignment assignment = requireAssignment(versionId, entryId);
        SchTask task = requireTask(assignment.getTaskId());
        SchedulingProblemLoader.Loaded loaded = loadProblem(version);
        SchedulingProblem problem = loaded.problem();
        var taskDef = problem.task(task.getId());
        List<Placement> existing = placements(versionId).stream()
                .filter(p -> !Objects.equals(p.taskId(), task.getId()))
                .toList();
        List<Long> roomIds = new ArrayList<>();
        if (Boolean.TRUE.equals(task.getRoomRequired())) {
            roomIds.addAll(problem.rooms().values().stream()
                    .filter(r -> RoomMatcher.mismatch(problem, taskDef, r) == null)
                    .sorted(Comparator.comparingInt((RoomDef r) -> Math.abs(r.capacity()
                            - problem.studentCount(taskDef))).thenComparing(RoomDef::code))
                    .map(RoomDef::id).toList());
        } else {
            roomIds.add(null);
        }
        List<ScheduleDtos.CandidateView> out = new ArrayList<>();
        for (int day : problem.teachingDays()) {
            for (int start : problem.periods().keySet()) {
                if (!problem.validSpan(start, task.getPeriodsPerSession())) {
                    continue;
                }
                int end = start + task.getPeriodsPerSession() - 1;
                for (Long roomId : roomIds) {
                    if (roomId != null && RoomMatcher.unavailable(problem, roomId, day, start, end,
                            problem.effectiveWeeks(taskDef))) {
                        continue;
                    }
                    Placement candidate = new Placement(task.getId(), assignment.getSessionIndex(), day, start,
                            roomId, false);
                    List<Placement> conflicts = validator.conflictsOf(problem, existing, candidate);
                    SchRoom room = roomId == null ? null : roomMapper.selectById(roomId);
                    out.add(new ScheduleDtos.CandidateView(day, start, end, roomId,
                            room == null ? null : room.getCode(), conflicts.isEmpty(),
                            conflicts.stream().map(c -> "任务 " + c.taskId() + " "
                                    + ScheduleViewSupport.timeText(c.day(), c.startPeriod(), c.startPeriod()))
                                    .toList()));
                    if (out.size() >= 300) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 复核

    /** 复核并固化：重写问题清单、更新已排课次/硬约束违反数/评分。 */
    @Transactional
    public ScheduleDtos.EvaluationView check(AuthenticatedUser user, long versionId) {
        Permissions.require(user, Permissions.SCHEDULE_ADJUST);
        SchVersion version = queryService.requireVersion(versionId);
        ScheduleDtos.EvaluationView view = queryService.evaluate(version);
        issueMapper.delete(new LambdaQueryWrapper<SchVersionIssue>().eq(SchVersionIssue::getVersionId, versionId));
        for (ScheduleDtos.ConflictView conflict : view.conflicts()) {
            SchVersionIssue row = new SchVersionIssue();
            row.setVersionId(versionId);
            row.setSeverity("ERROR");
            row.setCode(conflict.code());
            row.setTaskId(conflict.taskId());
            row.setMessage(conflict.message());
            issueMapper.insert(row);
        }
        versionMapper.update(null, new LambdaUpdateWrapper<SchVersion>()
                .eq(SchVersion::getId, versionId)
                .set(SchVersion::getHardViolations, view.hardViolations())
                .set(SchVersion::getPlacedSessions, view.placedSessions())
                .set(SchVersion::getScore, BigDecimal.valueOf(view.score()))
                .set(SchVersion::getSoftPenalty, view.softPenalty()));
        audit(user, "SCHEDULING_VERSION_CHECK", versionId, Map.of("hardViolations", view.hardViolations(),
                "score", view.score()));
        return view;
    }

    // ------------------------------------------------------------------ 局部重排

    /**
     * 以当前版本为基准创建局部重排任务：只放开指定任务，其余课次固定。
     * 不修改原版本，结果写入新版本。
     */
    /**
     * 注意：这里**不能**加 @Transactional。任务记录必须先在独立事务中提交，
     * 异步线程才能读到它；否则会出现“任务一直停在 QUEUED、永远不执行”。
     */
    public JobDtos.JobView reschedule(AuthenticatedUser user, long versionId, List<Long> taskIds,
                                      Integer timeLimitSeconds, String reason) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchVersion version = queryService.requireVersion(versionId);
        if (version.getStatus() == VersionStatus.PUBLISHED) {
            throw BusinessException.conflict("已发布版本不能直接重排，请先复制出新版本");
        }
        List<Long> allTasks = placements(versionId).stream().map(Placement::taskId).distinct().toList();
        List<Long> replan = taskIds == null || taskIds.isEmpty() ? allTasks : taskIds;
        if (replan.isEmpty()) {
            throw BusinessException.badRequest("该版本没有可重排的教学任务");
        }
        JobDtos.JobCreateRequest req = new JobDtos.JobCreateRequest(version.getSemesterId(),
                "局部重排 " + version.getName(), "INCREMENTAL", versionId, allTasks, null,
                timeLimitSeconds == null ? 60 : timeLimitSeconds, 4, 1, true, true, null, replan);
        JobDtos.JobView job = jobService.create(user, req);
        jobService.run(user, job.id());
        audit(user, "SCHEDULING_VERSION_RESCHEDULE", versionId, Map.of("jobId", job.id(),
                "tasks", replan.size()));
        return job;
    }

    // ------------------------------------------------------------------ helpers

    private void validateRoom(SchedulingProblem problem, com.campus.platform.scheduling.solver.model.TaskDef taskDef,
                              long roomId, int day, int start, int end) {
        RoomDef room = problem.rooms().get(roomId);
        if (room == null) {
            throw BusinessException.badRequest("教室不存在或已停用：" + roomId);
        }
        ConstraintCode mismatch = RoomMatcher.mismatch(problem, taskDef, room);
        if (mismatch != null) {
            throw BusinessException.conflict("教室 " + room.code() + " 不满足要求（" + mismatch.label()
                    + "，容量 " + room.capacity() + "，教学班人数 " + problem.studentCount(taskDef) + "）");
        }
        if (RoomMatcher.unavailable(problem, roomId, day, start, end, problem.effectiveWeeks(taskDef))) {
            throw BusinessException.conflict("教室 " + room.code() + " 在该时段维护或未开放");
        }
    }

    private void refresh(AuthenticatedUser user, SchVersion version) {
        ScheduleDtos.EvaluationView view = queryService.evaluate(version);
        versionMapper.update(null, new LambdaUpdateWrapper<SchVersion>()
                .eq(SchVersion::getId, version.getId())
                .set(SchVersion::getPlacedSessions, view.placedSessions())
                .set(SchVersion::getHardViolations, view.hardViolations())
                .set(SchVersion::getScore, BigDecimal.valueOf(view.score()))
                .set(SchVersion::getSoftPenalty, view.softPenalty())
                .set(SchVersion::getUpdatedBy, user.userId()));
    }

    private SchVersion editableVersion(long versionId) {
        SchVersion version = queryService.requireVersion(versionId);
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw BusinessException.conflict("只有草稿版本可以调整；已发布版本请先复制出新版本");
        }
        return version;
    }

    private SchAssignment requireAssignment(long versionId, long entryId) {
        SchAssignment assignment = assignmentMapper.selectById(entryId);
        if (assignment == null || !Objects.equals(assignment.getVersionId(), versionId)) {
            throw BusinessException.notFound("课时安排");
        }
        return assignment;
    }

    private SchTask requireTask(long taskId) {
        SchTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw BusinessException.notFound("教学任务");
        }
        return task;
    }

    private SchedulingProblemLoader.Loaded loadProblem(SchVersion version) {
        List<Long> taskIds = placements(version.getId()).stream().map(Placement::taskId).distinct().toList();
        return loader.load(new SchedulingProblemLoader.LoadRequest(version.getSemesterId(), taskIds, null, null,
                true, false, null));
    }

    private List<Placement> placements(long versionId) {
        return assignmentMapper.selectList(new LambdaQueryWrapper<SchAssignment>()
                        .eq(SchAssignment::getVersionId, versionId)).stream()
                .map(a -> new Placement(a.getTaskId(), a.getSessionIndex(), a.getDayOfWeek(), a.getStartPeriod(),
                        a.getRoomId(), Boolean.TRUE.equals(a.getLocked())))
                .toList();
    }

    private void log(AuthenticatedUser user, long versionId, long assignmentId, String action, Integer fromDay,
                     Integer fromPeriod, Long fromRoom, Integer toDay, Integer toPeriod, Long toRoom,
                     String reason) {
        SchAdjustmentLog row = new SchAdjustmentLog();
        row.setVersionId(versionId);
        row.setAssignmentId(assignmentId);
        row.setAction(action);
        row.setFromDay(fromDay);
        row.setFromPeriod(fromPeriod);
        row.setFromRoomId(fromRoom);
        row.setToDay(toDay);
        row.setToPeriod(toPeriod);
        row.setToRoomId(toRoom);
        row.setReason(reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason);
        row.setCreatedBy(user.userId());
        adjustmentMapper.insert(row);
    }

    private void audit(AuthenticatedUser user, String action, Long targetId, Map<String, ?> detail) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("schedule_entry", targetId);
        detail.forEach(b::detail);
        auditService.record(b.build());
    }

    static Map<String, Object> diff(Integer fromDay, Integer fromPeriod, Integer toDay, Integer toPeriod) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", TaskServiceSupport.slotText(fromDay, fromPeriod, null));
        m.put("to", TaskServiceSupport.slotText(toDay, toPeriod, null));
        return m;
    }
}
