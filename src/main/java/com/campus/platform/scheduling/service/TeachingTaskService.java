package com.campus.platform.scheduling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.entity.WeekParity;
import com.campus.platform.course.mapper.SemesterMapper;
import com.campus.platform.scheduling.dto.TaskDtos;
import com.campus.platform.scheduling.entity.SchAssignment;
import com.campus.platform.scheduling.entity.SchCourse;
import com.campus.platform.scheduling.entity.SchRoom;
import com.campus.platform.scheduling.entity.SchTask;
import com.campus.platform.scheduling.entity.SchTaskRoom;
import com.campus.platform.scheduling.entity.SchTaskTeacher;
import com.campus.platform.scheduling.entity.SchTeachingClass;
import com.campus.platform.scheduling.mapper.SchAssignmentMapper;
import com.campus.platform.scheduling.mapper.SchCourseMapper;
import com.campus.platform.scheduling.mapper.SchRoomMapper;
import com.campus.platform.scheduling.mapper.SchTaskMapper;
import com.campus.platform.scheduling.mapper.SchTaskRoomMapper;
import com.campus.platform.scheduling.mapper.SchTaskTeacherMapper;
import com.campus.platform.scheduling.mapper.SchTeachingClassMapper;
import com.campus.platform.scheduling.solver.SchedulingPrecheck;
import com.campus.platform.scheduling.solver.model.Issue;
import com.campus.platform.scheduling.solver.model.SolverStatus;
import com.campus.platform.scheduling.solver.model.Weeks;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 教学任务管理：创建/修改/批量导入导出、数据预检。
 * <p>
 * 学时校验口径：任务提供学时 = 单次节数 × 每周次数 × 有效教学周数（任务周次 ∩ 可教学周）。
 * 与 {@code total_hours} 不一致时允许保存（便于分批录入），但预检会阻止求解并给出具体差额。
 */
@Service
public class TeachingTaskService {

    static final String MODULE = "scheduling";
    private static final List<String> CSV_HEADER = List.of("code", "courseCode", "teachingClassCode",
            "teacherUsernames", "startWeek", "endWeek", "weekParity", "sessionsPerWeek", "periodsPerSession",
            "totalHours", "roomRequired", "note");

    private final SchTaskMapper taskMapper;
    private final SchTaskTeacherMapper taskTeacherMapper;
    private final SchTaskRoomMapper taskRoomMapper;
    private final SchCourseMapper courseMapper;
    private final SchTeachingClassMapper teachingClassMapper;
    private final SchRoomMapper roomMapper;
    private final SchAssignmentMapper assignmentMapper;
    private final SysUserMapper userMapper;
    private final SemesterMapper semesterMapper;
    private final SchedulingProblemLoader loader;
    private final AuditService auditService;

    public TeachingTaskService(SchTaskMapper taskMapper, SchTaskTeacherMapper taskTeacherMapper,
                               SchTaskRoomMapper taskRoomMapper, SchCourseMapper courseMapper,
                               SchTeachingClassMapper teachingClassMapper, SchRoomMapper roomMapper,
                               SchAssignmentMapper assignmentMapper, SysUserMapper userMapper,
                               SemesterMapper semesterMapper, SchedulingProblemLoader loader,
                               AuditService auditService) {
        this.taskMapper = taskMapper;
        this.taskTeacherMapper = taskTeacherMapper;
        this.taskRoomMapper = taskRoomMapper;
        this.courseMapper = courseMapper;
        this.teachingClassMapper = teachingClassMapper;
        this.roomMapper = roomMapper;
        this.assignmentMapper = assignmentMapper;
        this.userMapper = userMapper;
        this.semesterMapper = semesterMapper;
        this.loader = loader;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<TaskDtos.TaskView> list(AuthenticatedUser user, TaskDtos.TaskQuery query) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        Set<Long> teacherTaskIds = null;
        if (query.getTeacherId() != null) {
            teacherTaskIds = taskTeacherMapper.selectList(new LambdaQueryWrapper<SchTaskTeacher>()
                            .eq(SchTaskTeacher::getTeacherId, query.getTeacherId())).stream()
                    .map(SchTaskTeacher::getTaskId).collect(Collectors.toCollection(LinkedHashSet::new));
            if (teacherTaskIds.isEmpty()) {
                return PageResult.empty(query.getPage(), query.getSize());
            }
        }
        Page<SchTask> page = taskMapper.selectPage(query.toPage(), new LambdaQueryWrapper<SchTask>()
                .eq(query.getSemesterId() != null, SchTask::getSemesterId, query.getSemesterId())
                .eq(query.getOrgUnitId() != null, SchTask::getOrgUnitId, query.getOrgUnitId())
                .eq(query.getCourseId() != null, SchTask::getCourseId, query.getCourseId())
                .eq(query.getTeachingClassId() != null, SchTask::getTeachingClassId, query.getTeachingClassId())
                .eq(query.getStatus() != null, SchTask::getStatus, query.getStatus())
                .in(teacherTaskIds != null, SchTask::getId, teacherTaskIds)
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        w -> w.like(SchTask::getCode, query.getKeyword()).or().like(SchTask::getNote,
                                query.getKeyword()))
                .orderByAsc(SchTask::getCode));
        return PageResult.of(page, t -> views(List.of(t)).get(0));
    }

    public TaskDtos.TaskView get(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        return views(List.of(requireTask(id))).get(0);
    }

    public List<TaskDtos.TaskView> views(List<SchTask> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<Long> ids = tasks.stream().map(SchTask::getId).toList();
        Map<Long, List<Long>> teachers = new LinkedHashMap<>();
        Map<Long, Map<Long, String>> rolesByTask = new LinkedHashMap<>();
        for (SchTaskTeacher row : taskTeacherMapper.selectList(new LambdaQueryWrapper<SchTaskTeacher>()
                .in(SchTaskTeacher::getTaskId, ids))) {
            teachers.computeIfAbsent(row.getTaskId(), k -> new ArrayList<>()).add(row.getTeacherId());
            rolesByTask.computeIfAbsent(row.getTaskId(), k -> new LinkedHashMap<>())
                    .put(row.getTeacherId(), row.getTeacherRole());
        }
        Set<Long> teacherIds = teachers.values().stream().flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> teacherNames = teacherIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(teacherIds).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getDisplayName, (a, b) -> a));
        Map<Long, List<SchTaskRoom>> rooms = new LinkedHashMap<>();
        for (SchTaskRoom row : taskRoomMapper.selectList(new LambdaQueryWrapper<SchTaskRoom>()
                .in(SchTaskRoom::getTaskId, ids))) {
            rooms.computeIfAbsent(row.getTaskId(), k -> new ArrayList<>()).add(row);
        }
        Map<Long, SchRoom> roomMap = roomMapper.selectList(null).stream()
                .collect(Collectors.toMap(SchRoom::getId, r -> r, (a, b) -> a));
        Map<Long, SchCourse> courses = courseMapper.selectBatchIds(tasks.stream().map(SchTask::getCourseId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(SchCourse::getId, c -> c, (a, b) -> a));
        Map<Long, SchTeachingClass> tcs = teachingClassMapper.selectBatchIds(tasks.stream()
                        .map(SchTask::getTeachingClassId).distinct().toList()).stream()
                .collect(Collectors.toMap(SchTeachingClass::getId, t -> t, (a, b) -> a));
        return tasks.stream().map(t -> {
            SchCourse course = courses.get(t.getCourseId());
            SchTeachingClass tc = tcs.get(t.getTeachingClassId());
            List<Long> teacherIdsOfTask = teachers.getOrDefault(t.getId(), List.of());
            Map<Long, String> roleByTeacher = rolesByTask.getOrDefault(t.getId(), Map.of());
            int provided = 0;
            boolean consistent = false;
            try {
                var ctx = loader.calendar(t.getSemesterId());
                provided = SchedulingProblemLoader.providedHours(ctx, t);
                consistent = provided == t.getTotalHours();
            } catch (RuntimeException ex) {
                provided = 0;
            }
            return new TaskDtos.TaskView(t.getId(), t.getSemesterId(), t.getCode(), t.getCourseId(),
                    course == null ? null : course.getCode(), course == null ? null : course.getName(),
                    t.getTeachingClassId(), tc == null ? null : tc.getName(),
                    tc == null ? List.of() : List.of(tc.getName()),
                    t.getOrgUnitId(), t.getStartWeek(), t.getEndWeek(), t.getWeekParity(),
                    weekList(t), t.getSessionsPerWeek(), t.getPeriodsPerSession(), t.getTotalHours(), provided,
                    consistent, t.getRoomRequired(), t.getRoomType(),
                    ResourceCatalogService.split(t.getRequiredFeatures()), t.getPriority(), t.getAllowSameDay(),
                    t.getUniformStart(), t.getStatus(), t.getNote(),
                    teacherIdsOfTask.stream().map(id -> new TaskDtos.TeacherRef(id, teacherNames.get(id),
                            roleByTeacher.getOrDefault(id, "MAIN"))).toList(),
                    rooms.getOrDefault(t.getId(), List.of()).stream()
                            .map(r -> new TaskDtos.RoomRef(r.getRoomId(),
                                    roomMap.containsKey(r.getRoomId()) ? roomMap.get(r.getRoomId()).getCode() : null,
                                    r.getMode())).toList(),
                    t.getVersion());
        }).toList();
    }

    private static List<Integer> weekList(SchTask task) {
        WeekParity parity;
        try {
            parity = task.getWeekParity() == null ? WeekParity.ALL : WeekParity.valueOf(task.getWeekParity());
        } catch (IllegalArgumentException ex) {
            parity = WeekParity.ALL;
        }
        long mask = Weeks.range(task.getStartWeek(), task.getEndWeek(), parity);
        List<Integer> out = new ArrayList<>();
        for (int w = 1; w <= Weeks.MAX_WEEKS; w++) {
            if (Weeks.has(mask, w)) {
                out.add(w);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 写入

    @Transactional
    public TaskDtos.TaskView create(AuthenticatedUser user, TaskDtos.TaskRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        validate(req, null);
        if (taskMapper.selectCount(new LambdaQueryWrapper<SchTask>()
                .eq(SchTask::getSemesterId, req.semesterId()).eq(SchTask::getCode, req.code())) > 0) {
            throw BusinessException.conflict("同一学期内任务编号 " + req.code() + " 已存在");
        }
        SchTask task = new SchTask();
        apply(task, req);
        task.setVersion(0);
        task.setCreatedBy(user.userId());
        task.setUpdatedBy(user.userId());
        taskMapper.insert(task);
        replaceTeachers(task.getId(), req.teacherIds());
        replaceRooms(task.getId(), req.rooms());
        audit(user, "SCHEDULING_TASK_CREATE", task.getId(), Map.of("code", task.getCode()));
        return views(List.of(task)).get(0);
    }

    @Transactional
    public TaskDtos.TaskView update(AuthenticatedUser user, long id, TaskDtos.TaskRequest req) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchTask task = requireTask(id);
        if (req.version() != null && !req.version().equals(task.getVersion())) {
            throw BusinessException.conflict("教学任务已被他人修改，请刷新后重试");
        }
        validate(req, id);
        apply(task, req);
        // 乐观锁由 MyBatis-Plus 的 @Version 处理：这里不能手工 +1，否则 WHERE version 永远不匹配
        task.setUpdatedBy(user.userId());
        taskMapper.updateById(task);
        replaceTeachers(id, req.teacherIds());
        replaceRooms(id, req.rooms());
        audit(user, "SCHEDULING_TASK_UPDATE", id, Map.of("code", task.getCode()));
        return views(List.of(task)).get(0);
    }

    @Transactional
    public void delete(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        SchTask task = requireTask(id);
        Long used = assignmentMapper.selectCount(new LambdaQueryWrapper<SchAssignment>()
                .eq(SchAssignment::getTaskId, id));
        if (used != null && used > 0) {
            throw new BusinessException(com.campus.platform.common.exception.ErrorCode.CONFLICT,
                    "该教学任务已出现在课表版本中，不能删除；可将状态改为 DISABLED");
        }
        taskTeacherMapper.delete(new LambdaQueryWrapper<SchTaskTeacher>().eq(SchTaskTeacher::getTaskId, id));
        taskRoomMapper.delete(new LambdaQueryWrapper<SchTaskRoom>().eq(SchTaskRoom::getTaskId, id));
        taskMapper.deleteById(id);
        audit(user, "SCHEDULING_TASK_DELETE", id, Map.of("code", task.getCode()));
    }

    // ------------------------------------------------------------------ 导入导出

    @Transactional
    public TaskDtos.ImportResult importCsv(AuthenticatedUser user, long semesterId, String csv) {
        Permissions.require(user, Permissions.SCHEDULE_MANAGE);
        if (csv == null || csv.isBlank()) {
            throw BusinessException.badRequest("CSV 内容为空");
        }
        if (semesterMapper.selectById(semesterId) == null) {
            throw BusinessException.notFound("学期");
        }
        List<TaskDtos.ImportRowError> errors = new ArrayList<>();
        int created = 0;
        int updated = 0;
        // Excel 另存为 CSV 会带 UTF-8 BOM，不剥掉会让第一列表头变成 “\uFEFFcode”，整表当成数据
        String content = csv.startsWith("\uFEFF") ? csv.substring(1) : csv;
        String[] lines = content.split("\\r?\\n");
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (lineNo == 1 && line.toLowerCase().startsWith("code")) {
                continue;
            }
            String[] cells = line.split(",", -1);
            for (int i = 0; i < cells.length; i++) {
                String cell = cells[i].trim();
                if (cell.length() >= 2 && cell.startsWith("\"") && cell.endsWith("\"")) {
                    cell = cell.substring(1, cell.length() - 1).replace("\"\"", "\"");
                }
                cells[i] = cell;
            }
            try {
                if (cells.length < 10) {
                    throw BusinessException.badRequest("列数不足，至少需要 10 列（" + String.join(",", CSV_HEADER)
                            + "）");
                }
                String code = cells[0].trim();
                String courseCode = cells[1].trim();
                String tcCode = cells[2].trim();
                List<String> usernames = Arrays.stream(cells[3].split("[;|]")).map(String::trim)
                        .filter(s -> !s.isEmpty()).toList();
                int startWeek = Integer.parseInt(cells[4].trim());
                int endWeek = Integer.parseInt(cells[5].trim());
                String parity = cells[6].trim().isEmpty() ? "ALL" : cells[6].trim().toUpperCase();
                int sessions = Integer.parseInt(cells[7].trim());
                int periods = Integer.parseInt(cells[8].trim());
                int totalHours = Integer.parseInt(cells[9].trim());
                boolean roomRequired = cells.length < 11 || cells[10].trim().isEmpty()
                        || Boolean.parseBoolean(cells[10].trim());
                String note = cells.length < 12 ? null : cells[11].trim();

                SchCourse course = courseMapper.selectOne(new LambdaQueryWrapper<SchCourse>()
                        .eq(SchCourse::getCode, courseCode));
                if (course == null) {
                    throw BusinessException.badRequest("课程代码不存在：" + courseCode);
                }
                SchTeachingClass tc = teachingClassMapper.selectOne(new LambdaQueryWrapper<SchTeachingClass>()
                        .eq(SchTeachingClass::getSemesterId, semesterId)
                        .eq(SchTeachingClass::getCode, tcCode));
                if (tc == null) {
                    throw BusinessException.badRequest("教学班编号不存在：" + tcCode);
                }
                List<Long> teacherIds = new ArrayList<>();
                for (String username : usernames) {
                    SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getUsername, username));
                    if (u == null) {
                        throw BusinessException.badRequest("教师工号不存在：" + username);
                    }
                    teacherIds.add(u.getId());
                }
                if (teacherIds.isEmpty()) {
                    throw BusinessException.badRequest("至少需要一名教师");
                }
                SchTask existing = taskMapper.selectOne(new LambdaQueryWrapper<SchTask>()
                        .eq(SchTask::getSemesterId, semesterId).eq(SchTask::getCode, code));
                SchTask task = existing == null ? new SchTask() : existing;
                task.setSemesterId(semesterId);
                task.setCode(code);
                task.setCourseId(course.getId());
                task.setTeachingClassId(tc.getId());
                task.setOrgUnitId(course.getOrgUnitId());
                task.setStartWeek(startWeek);
                task.setEndWeek(endWeek);
                task.setWeekParity(parity);
                task.setSessionsPerWeek(sessions);
                task.setPeriodsPerSession(periods);
                task.setTotalHours(totalHours);
                task.setRoomRequired(roomRequired);
                task.setStatus("ACTIVE");
                task.setNote(note);
                task.setUpdatedBy(user.userId());
                if (existing == null) {
                    task.setVersion(0);
                    task.setCreatedBy(user.userId());
                    if (task.getAllowSameDay() == null) {
                        task.setAllowSameDay(false);
                        task.setUniformStart(false);
                    }
                    taskMapper.insert(task);
                    created++;
                } else {
                    taskMapper.updateById(task);
                    updated++;
                }
                replaceTeachers(task.getId(), teacherIds);
            } catch (RuntimeException ex) {
                errors.add(new TaskDtos.ImportRowError(lineNo, "IMPORT_FAILED", ex.getMessage()));
            }
        }
        audit(user, "SCHEDULING_TASK_IMPORT", semesterId, Map.of("created", created, "updated", updated,
                "failed", errors.size()));
        return new TaskDtos.ImportResult(created + updated + errors.size(), created, updated, errors.size(),
                errors);
    }

    public String exportCsv(AuthenticatedUser user, long semesterId) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        List<SchTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<SchTask>()
                .eq(SchTask::getSemesterId, semesterId).orderByAsc(SchTask::getCode));
        Map<Long, SchCourse> courses = courseMapper.selectBatchIds(tasks.stream().map(SchTask::getCourseId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(SchCourse::getId, c -> c, (a, b) -> a));
        Map<Long, SchTeachingClass> tcs = teachingClassMapper.selectBatchIds(tasks.stream()
                        .map(SchTask::getTeachingClassId).distinct().toList()).stream()
                .collect(Collectors.toMap(SchTeachingClass::getId, t -> t, (a, b) -> a));
        Map<Long, List<String>> teachers = new LinkedHashMap<>();
        for (SchTaskTeacher row : taskTeacherMapper.selectList(new LambdaQueryWrapper<SchTaskTeacher>()
                .in(SchTaskTeacher::getTaskId, tasks.stream().map(SchTask::getId).toList()))) {
            SysUser u = userMapper.selectById(row.getTeacherId());
            teachers.computeIfAbsent(row.getTaskId(), k -> new ArrayList<>())
                    .add(u == null ? String.valueOf(row.getTeacherId()) : u.getUsername());
        }
        StringBuilder sb = new StringBuilder(String.join(",", CSV_HEADER)).append('\n');
        for (SchTask t : tasks) {
            SchCourse c = courses.get(t.getCourseId());
            SchTeachingClass tc = tcs.get(t.getTeachingClassId());
            sb.append(csv(t.getCode())).append(',')
                    .append(csv(c == null ? "" : c.getCode())).append(',')
                    .append(csv(tc == null ? "" : tc.getCode())).append(',')
                    .append(csv(String.join("|", teachers.getOrDefault(t.getId(), List.of())))).append(',')
                    .append(t.getStartWeek()).append(',')
                    .append(t.getEndWeek()).append(',')
                    .append(t.getWeekParity() == null ? "ALL" : t.getWeekParity()).append(',')
                    .append(t.getSessionsPerWeek()).append(',')
                    .append(t.getPeriodsPerSession()).append(',')
                    .append(t.getTotalHours()).append(',')
                    .append(Boolean.TRUE.equals(t.getRoomRequired())).append(',')
                    .append(csv(t.getNote() == null ? "" : t.getNote())).append('\n');
        }
        return sb.toString();
    }

    public byte[] exportCsvBytes(AuthenticatedUser user, long semesterId) {
        return exportCsv(user, semesterId).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 预检

    public TaskDtos.PrecheckView precheck(AuthenticatedUser user, long semesterId, Long orgUnitId,
                                          List<Long> taskIds) {
        Permissions.require(user, Permissions.SCHEDULE_READ);
        List<Issue> issues;
        String summary;
        try {
            SchedulingProblemLoader.Loaded loaded = loader.load(new SchedulingProblemLoader.LoadRequest(
                    semesterId, taskIds, orgUnitId, null, true, false, null));
            issues = new ArrayList<>(SchedulingPrecheck.check(loaded.problem()));
            summary = "共 " + loaded.tasks().size() + " 个教学任务，每周 "
                    + loaded.problem().tasks().stream().mapToInt(t -> t.sessionsPerWeek()).sum() + " 次课；"
                    + SchedulingPrecheck.roomCapacitySummary(loaded.problem());
        } catch (BusinessException ex) {
            issues = List.of(Issue.error("PRECHECK_FAILED", null, ex.getMessage()));
            summary = ex.getMessage();
        }
        long errors = issues.stream().filter(i -> i.severity() == Issue.Severity.ERROR).count();
        long warnings = issues.stream().filter(i -> i.severity() == Issue.Severity.WARNING).count();
        return new TaskDtos.PrecheckView((int) errors, (int) warnings, errors == 0, summary,
                issues.stream().map(TaskServiceSupport::issueView).toList());
    }

    // ------------------------------------------------------------------ helpers

    public SchTask requireTask(long id) {
        SchTask task = taskMapper.selectById(id);
        if (task == null) {
            throw BusinessException.notFound("教学任务");
        }
        return task;
    }

    private void validate(TaskDtos.TaskRequest req, Long existingId) {
        if (semesterMapper.selectById(req.semesterId()) == null) {
            throw BusinessException.notFound("学期");
        }
        SchCourse course = courseMapper.selectById(req.courseId());
        if (course == null) {
            throw BusinessException.badRequest("课程不存在：" + req.courseId());
        }
        if (!"ACTIVE".equals(course.getStatus())) {
            throw BusinessException.badRequest("课程 " + course.getCode() + " 已停用");
        }
        SchTeachingClass tc = teachingClassMapper.selectById(req.teachingClassId());
        if (tc == null) {
            throw BusinessException.badRequest("教学班不存在：" + req.teachingClassId());
        }
        if (!Objects.equals(tc.getSemesterId(), req.semesterId())) {
            throw BusinessException.badRequest("教学班属于其它学期");
        }
        if (req.endWeek() < req.startWeek()) {
            throw BusinessException.badRequest("结束周不能早于开始周");
        }
        if (req.weekParity() != null && !Set.of("ALL", "ODD", "EVEN").contains(req.weekParity())) {
            throw BusinessException.badRequest("单双周必须是 ALL/ODD/EVEN");
        }
        if (req.teacherIds() == null || req.teacherIds().isEmpty()) {
            throw BusinessException.badRequest("教学任务至少需要一名教师");
        }
        List<SysUser> teachers = userMapper.selectBatchIds(req.teacherIds());
        if (teachers.size() != new LinkedHashSet<>(req.teacherIds()).size()) {
            throw BusinessException.badRequest("存在无效的教师 ID");
        }
        if (req.rooms() != null) {
            for (TaskDtos.RoomRef ref : req.rooms()) {
                if (roomMapper.selectById(ref.roomId()) == null) {
                    throw BusinessException.badRequest("教室不存在：" + ref.roomId());
                }
                if (ref.mode() != null && !Set.of("REQUIRED", "PREFERRED", "FORBIDDEN").contains(ref.mode())) {
                    throw BusinessException.badRequest("教室模式必须是 REQUIRED/PREFERRED/FORBIDDEN");
                }
            }
        }
        if (existingId != null) {
            Long dup = taskMapper.selectCount(new LambdaQueryWrapper<SchTask>()
                    .eq(SchTask::getSemesterId, req.semesterId())
                    .eq(SchTask::getCode, req.code())
                    .ne(SchTask::getId, existingId));
            if (dup != null && dup > 0) {
                throw BusinessException.conflict("同一学期内任务编号 " + req.code() + " 已存在");
            }
        }
    }

    private void apply(SchTask task, TaskDtos.TaskRequest req) {
        task.setSemesterId(req.semesterId());
        task.setCode(req.code().trim());
        task.setCourseId(req.courseId());
        task.setTeachingClassId(req.teachingClassId());
        task.setOrgUnitId(req.orgUnitId());
        task.setStartWeek(req.startWeek());
        task.setEndWeek(req.endWeek());
        task.setWeekParity(req.weekParity() == null ? "ALL" : req.weekParity());
        task.setSessionsPerWeek(req.sessionsPerWeek());
        task.setPeriodsPerSession(req.periodsPerSession());
        task.setTotalHours(req.totalHours() != null ? req.totalHours()
                : req.sessionsPerWeek() * req.periodsPerSession());
        task.setRoomRequired(req.roomRequired() == null || req.roomRequired());
        task.setRoomType(req.roomType());
        task.setRequiredFeatures(req.requiredFeatures() == null ? null : String.join(",",
                req.requiredFeatures()));
        task.setPriority(req.priority());
        task.setAllowSameDay(Boolean.TRUE.equals(req.allowSameDay()));
        task.setUniformStart(Boolean.TRUE.equals(req.uniformStart()));
        task.setLinkedCourseId(req.linkedCourseId());
        task.setStatus(req.status() == null ? "ACTIVE" : req.status());
        task.setNote(req.note());
    }

    private void replaceTeachers(long taskId, List<Long> teacherIds) {
        taskTeacherMapper.delete(new LambdaQueryWrapper<SchTaskTeacher>().eq(SchTaskTeacher::getTaskId, taskId));
        boolean first = true;
        for (Long teacherId : new LinkedHashSet<>(teacherIds)) {
            SchTaskTeacher row = new SchTaskTeacher();
            row.setTaskId(taskId);
            row.setTeacherId(teacherId);
            row.setTeacherRole(first ? "MAIN" : "CO");
            taskTeacherMapper.insert(row);
            first = false;
        }
    }

    private void replaceRooms(long taskId, List<TaskDtos.RoomRef> rooms) {
        taskRoomMapper.delete(new LambdaQueryWrapper<SchTaskRoom>().eq(SchTaskRoom::getTaskId, taskId));
        if (rooms == null) {
            return;
        }
        for (TaskDtos.RoomRef ref : rooms) {
            SchTaskRoom row = new SchTaskRoom();
            row.setTaskId(taskId);
            row.setRoomId(ref.roomId());
            row.setMode(ref.mode() == null ? "REQUIRED" : ref.mode());
            taskRoomMapper.insert(row);
        }
    }

    static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    static String taskStatusName(SolverStatus status) {
        return status == null ? null : status.name();
    }

    private void audit(AuthenticatedUser user, String action, Long targetId, Map<String, ?> detail) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("teaching_task", targetId);
        detail.forEach(b::detail);
        auditService.record(b.build());
    }
}
