package com.campus.platform.scheduling.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.entity.WeekParity;
import com.campus.platform.scheduling.entity.SchAssignment;
import com.campus.platform.scheduling.entity.SchCalendarDate;
import com.campus.platform.scheduling.entity.SchCalendarWeek;
import com.campus.platform.scheduling.entity.SchCampusTravel;
import com.campus.platform.scheduling.entity.SchClassProfile;
import com.campus.platform.scheduling.entity.SchCourse;
import com.campus.platform.scheduling.entity.SchPeriod;
import com.campus.platform.scheduling.entity.SchRoom;
import com.campus.platform.scheduling.entity.SchSemesterConfig;
import com.campus.platform.scheduling.entity.SchTask;
import com.campus.platform.scheduling.entity.SchTaskRoom;
import com.campus.platform.scheduling.entity.SchTaskTeacher;
import com.campus.platform.scheduling.entity.SchTeachingClass;
import com.campus.platform.scheduling.entity.SchTeachingClassMember;
import com.campus.platform.scheduling.entity.SchTeacherProfile;
import com.campus.platform.scheduling.entity.SchTimeRule;
import com.campus.platform.scheduling.mapper.SchAssignmentMapper;
import com.campus.platform.scheduling.mapper.SchCampusTravelMapper;
import com.campus.platform.scheduling.mapper.SchClassProfileMapper;
import com.campus.platform.scheduling.mapper.SchCourseMapper;
import com.campus.platform.scheduling.mapper.SchRoomMapper;
import com.campus.platform.scheduling.mapper.SchTaskMapper;
import com.campus.platform.scheduling.mapper.SchTaskRoomMapper;
import com.campus.platform.scheduling.mapper.SchTaskTeacherMapper;
import com.campus.platform.scheduling.mapper.SchTeachingClassMapper;
import com.campus.platform.scheduling.mapper.SchTeachingClassMemberMapper;
import com.campus.platform.scheduling.mapper.SchTeacherProfileMapper;
import com.campus.platform.scheduling.solver.model.ClassDef;
import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.PeriodDef;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.RoomDef;
import com.campus.platform.scheduling.solver.model.RuleTarget;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.campus.platform.scheduling.solver.model.TeacherDef;
import com.campus.platform.scheduling.solver.model.TeachingClassDef;
import com.campus.platform.scheduling.solver.model.TimeRule;
import com.campus.platform.scheduling.solver.model.TimeRuleKind;
import com.campus.platform.scheduling.solver.model.Weeks;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从数据库装配一次排课求解所需的完整输入。
 * <p>
 * 所有查询按“一次批量”完成（按 ID 集合 IN 查询），避免在任务循环里逐条查库；
 * 装配结果 {@link SchedulingProblem} 与数据库无关，可被求解器、复核器、评分器共享。
 */
@Component
public class SchedulingProblemLoader {

    private final TermCalendarService termService;
    private final SchTaskMapper taskMapper;
    private final SchTaskTeacherMapper taskTeacherMapper;
    private final SchTaskRoomMapper taskRoomMapper;
    private final SchCourseMapper courseMapper;
    private final SchTeachingClassMapper teachingClassMapper;
    private final SchTeachingClassMemberMapper memberMapper;
    private final SchClassProfileMapper classProfileMapper;
    private final SchRoomMapper roomMapper;
    private final SchCampusTravelMapper travelMapper;
    private final SchAssignmentMapper assignmentMapper;
    private final SysUserMapper userMapper;
    private final SysOrgUnitMapper orgUnitMapper;
    private final SchTeacherProfileMapper teacherProfileMapper;
    private final RuleService ruleService;

    public SchedulingProblemLoader(TermCalendarService termService, SchTaskMapper taskMapper,
                                   SchTaskTeacherMapper taskTeacherMapper, SchTaskRoomMapper taskRoomMapper,
                                   SchCourseMapper courseMapper, SchTeachingClassMapper teachingClassMapper,
                                   SchTeachingClassMemberMapper memberMapper, SchClassProfileMapper classProfileMapper,
                                   SchRoomMapper roomMapper, SchCampusTravelMapper travelMapper,
                                   SchAssignmentMapper assignmentMapper, SysUserMapper userMapper,
                                   SysOrgUnitMapper orgUnitMapper, SchTeacherProfileMapper teacherProfileMapper,
                                   RuleService ruleService) {
        this.termService = termService;
        this.taskMapper = taskMapper;
        this.taskTeacherMapper = taskTeacherMapper;
        this.taskRoomMapper = taskRoomMapper;
        this.courseMapper = courseMapper;
        this.teachingClassMapper = teachingClassMapper;
        this.memberMapper = memberMapper;
        this.classProfileMapper = classProfileMapper;
        this.roomMapper = roomMapper;
        this.travelMapper = travelMapper;
        this.assignmentMapper = assignmentMapper;
        this.userMapper = userMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.teacherProfileMapper = teacherProfileMapper;
        this.ruleService = ruleService;
    }

    // ------------------------------------------------------------------ 日历上下文

    /**
     * @param teachableWeeks 可教学周位图（排除考试/实践/假期周）
     * @param lostWeeksByDay  按星期：因节假日/停课损失课次的周次位图
     * @param extraWeeksByDay 按星期：调休/补课额外上课的周次位图
     */
    public record CalendarContext(SchSemesterConfig config, List<SchPeriod> periods, List<Integer> teachingDays,
                                  long teachableWeeks, Map<Integer, Long> lostWeeksByDay,
                                  Map<Integer, Long> extraWeeksByDay) {
    }

    public CalendarContext calendar(long semesterId) {
        SchSemesterConfig config = termService.requireConfig(semesterId);
        List<SchPeriod> periods = termService.periodRows(semesterId);
        if (periods.isEmpty()) {
            throw BusinessException.rule("学期尚未配置节次作息，请先调用 PUT /api/v1/scheduling/terms/"
                    + semesterId + "/periods");
        }
        List<Integer> days = TermCalendarService.parseDays(config.getTeachingDays());
        long all = Weeks.all(config.getTeachingWeeks());
        long nonTeaching = 0;
        for (SchCalendarWeek week : termService.weekRows(semesterId)) {
            if (!Boolean.TRUE.equals(week.getTeachingAllowed())) {
                nonTeaching |= Weeks.bit(week.getWeekNo());
            }
        }
        long teachable = all & ~nonTeaching;
        Map<Integer, Long> lost = new HashMap<>();
        Map<Integer, Long> extra = new HashMap<>();
        for (SchCalendarDate date : termService.dateRows(semesterId)) {
            int week = TermCalendarService.weekOf(config, date.getEventDate());
            if (week == 0) {
                continue;
            }
            int dow = date.getEventDate().getDayOfWeek().getValue();
            switch (date.getDateType()) {
                case "HOLIDAY", "SUSPENDED" -> lost.merge(dow, Weeks.bit(week), (a, b) -> a | b);
                case "WORKDAY" -> {
                    if (date.getFollowDayOfWeek() != null) {
                        extra.merge(date.getFollowDayOfWeek(), Weeks.bit(week), (a, b) -> a | b);
                    }
                }
                case "MAKEUP" -> {
                    if (date.getFollowDate() != null) {
                        int makeDow = date.getFollowDate().getDayOfWeek().getValue();
                        extra.merge(makeDow, Weeks.bit(week), (a, b) -> a | b);
                    }
                }
                default -> {
                }
            }
        }
        return new CalendarContext(config, periods, days, teachable, lost, extra);
    }

    /** 任务的有效周次（任务周次 ∩ 可教学周）。 */
    public static long effectiveWeeks(CalendarContext ctx, SchTask task) {
        WeekParity parity;
        try {
            parity = task.getWeekParity() == null ? WeekParity.ALL : WeekParity.valueOf(task.getWeekParity());
        } catch (IllegalArgumentException ex) {
            parity = WeekParity.ALL;
        }
        long mask = Weeks.range(task.getStartWeek(), task.getEndWeek(), parity);
        return mask & ctx.teachableWeeks();
    }

    /** 任务按定义能提供的学时。 */
    public static int providedHours(CalendarContext ctx, SchTask task) {
        return task.getPeriodsPerSession() * task.getSessionsPerWeek()
                * Weeks.count(effectiveWeeks(ctx, task));
    }

    // ------------------------------------------------------------------ 装配

    /**
     * @param taskIds        参与排课的任务；为空表示学期内全部启用任务
     * @param orgUnitId      按开课单位过滤（为空不限制）
     * @param baseVersionId  基准版本：提供锁定课次与“尽量少改动”的参照
     * @param keepLocked     是否保留基准版本中锁定的课次
     * @param incremental    true=增量排课，基准版本中所有已排课次视为固定
     */
    public record LoadRequest(long semesterId, List<Long> taskIds, Long orgUnitId, Long baseVersionId,
                              boolean keepLocked, boolean incremental, List<Long> replanTaskIds,
                              Map<ConstraintCode, Integer> weightOverrides) {

        /** 兼容旧调用：不指定局部重排范围。 */
        public LoadRequest(long semesterId, List<Long> taskIds, Long orgUnitId, Long baseVersionId,
                           boolean keepLocked, boolean incremental,
                           Map<ConstraintCode, Integer> weightOverrides) {
            this(semesterId, taskIds, orgUnitId, baseVersionId, keepLocked, incremental, null, weightOverrides);
        }
    }

    /** 装配结果：问题数据 + 原始实体（供接口回显名称）。 */
    public record Loaded(SchedulingProblem problem, CalendarContext calendar, List<SchTask> tasks,
                         Map<Long, SchCourse> courses, Map<Long, SchTeachingClass> teachingClasses,
                         Map<Long, List<String>> classNames, Map<Long, List<String>> teacherNames,
                         Map<Long, SchRoom> rooms) {
    }

    public Loaded load(LoadRequest req) {
        CalendarContext ctx = calendar(req.semesterId());
        boolean explicitTasks = req.taskIds() != null && !req.taskIds().isEmpty();
        // 排课范围用“空 taskIds”表达，此时只取启用任务；
        // 复核/发布/调整会显式传入版本里的任务，即使任务后来被停用也必须能装配出来，
        // 否则它的课时会从统计和复核里静默消失。
        LambdaQueryWrapper<SchTask> w = new LambdaQueryWrapper<SchTask>()
                .eq(SchTask::getSemesterId, req.semesterId())
                .eq(!explicitTasks, SchTask::getStatus, "ACTIVE")
                .eq(req.orgUnitId() != null, SchTask::getOrgUnitId, req.orgUnitId())
                .in(explicitTasks, SchTask::getId, req.taskIds())
                .orderByAsc(SchTask::getCode);
        List<SchTask> tasks = taskMapper.selectList(w);
        if (tasks.isEmpty()) {
            throw BusinessException.rule("没有可排课的教学任务（学期 " + req.semesterId()
                    + "，状态 ACTIVE）。请先创建教学任务或检查过滤条件");
        }
        List<Long> taskIds = tasks.stream().map(SchTask::getId).toList();
        Map<Long, List<Long>> taskTeachers = new LinkedHashMap<>();
        for (SchTaskTeacher row : taskTeacherMapper.selectList(new LambdaQueryWrapper<SchTaskTeacher>()
                .in(SchTaskTeacher::getTaskId, taskIds))) {
            taskTeachers.computeIfAbsent(row.getTaskId(), k -> new ArrayList<>()).add(row.getTeacherId());
        }
        Map<Long, List<SchTaskRoom>> taskRooms = new LinkedHashMap<>();
        for (SchTaskRoom row : taskRoomMapper.selectList(new LambdaQueryWrapper<SchTaskRoom>()
                .in(SchTaskRoom::getTaskId, taskIds))) {
            taskRooms.computeIfAbsent(row.getTaskId(), k -> new ArrayList<>()).add(row);
        }
        Map<Long, SchCourse> courses = courseMapper.selectBatchIds(tasks.stream().map(SchTask::getCourseId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(SchCourse::getId, c -> c, (a, b) -> a));
        List<Long> tcIds = tasks.stream().map(SchTask::getTeachingClassId).distinct().toList();
        Map<Long, SchTeachingClass> teachingClasses = teachingClassMapper.selectBatchIds(tcIds).stream()
                .collect(Collectors.toMap(SchTeachingClass::getId, t -> t, (a, b) -> a));
        List<SchTeachingClassMember> members = memberMapper.selectList(new LambdaQueryWrapper<SchTeachingClassMember>()
                .in(SchTeachingClassMember::getTeachingClassId, tcIds));
        Map<Long, List<TeachingClassDef.Member>> membersByTc = new LinkedHashMap<>();
        Map<Long, List<SchTeachingClassMember>> memberRows = new LinkedHashMap<>();
        for (SchTeachingClassMember m : members) {
            membersByTc.computeIfAbsent(m.getTeachingClassId(), k -> new ArrayList<>())
                    .add(new TeachingClassDef.Member(m.getOrgUnitId(), Boolean.TRUE.equals(m.getFullClass())));
            memberRows.computeIfAbsent(m.getTeachingClassId(), k -> new ArrayList<>()).add(m);
        }
        Map<Long, SchClassProfile> classProfiles = classProfileMapper.selectList(null).stream()
                .collect(Collectors.toMap(SchClassProfile::getOrgUnitId, p -> p, (a, b) -> a));
        Set<Long> classIds = members.stream().map(SchTeachingClassMember::getOrgUnitId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SysOrgUnit> classOrgs = classIds.isEmpty() ? Map.of()
                : orgUnitMapper.selectBatchIds(classIds).stream()
                .collect(Collectors.toMap(SysOrgUnit::getId, o -> o, (a, b) -> a));
        Map<Long, List<String>> classNames = new LinkedHashMap<>();
        classIds.forEach(id -> classNames.put(id, List.of(classOrgs.containsKey(id)
                ? classOrgs.get(id).getName() : String.valueOf(id))));

        Map<Long, Integer> tcStudents = new LinkedHashMap<>();
        Map<Long, TeachingClassDef> tcDefs = new LinkedHashMap<>();
        for (SchTask task : tasks) {
            SchTeachingClass tc = teachingClasses.get(task.getTeachingClassId());
            if (tc == null) {
                throw BusinessException.rule("教学任务 " + task.getCode() + " 引用的教学班不存在");
            }
            List<TeachingClassDef.Member> ms = membersByTc.getOrDefault(tc.getId(), List.of());
            int count = tc.getStudentCount() != null ? tc.getStudentCount()
                    : computeStudents(memberRows.getOrDefault(tc.getId(), List.of()), classProfiles);
            tcStudents.put(tc.getId(), count);
            tcDefs.put(tc.getId(), new TeachingClassDef(tc.getId(), tc.getName(), count, ms));
            if (ms.isEmpty()) {
                throw BusinessException.rule("教学班 " + tc.getCode() + " 没有成员，无法确定上课学生群体");
            }
        }

        Set<Long> teacherIds = taskTeachers.values().stream().flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SysUser> users = teacherIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(teacherIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        List<SchTeacherProfile> profiles = teacherIds.isEmpty() ? List.of()
                : teacherProfileMapper.selectList(new LambdaQueryWrapper<SchTeacherProfile>()
                .in(SchTeacherProfile::getTeacherId, teacherIds));
        Map<Long, SchTeacherProfile> profileMap = profiles.stream()
                .collect(Collectors.toMap(SchTeacherProfile::getTeacherId, p -> p, (a, b) -> a));
        List<TeacherDef> teacherDefs = new ArrayList<>();
        Map<Long, List<String>> teacherNames = new LinkedHashMap<>();
        for (Long id : teacherIds) {
            var profile = profileMap.get(id);
            teacherDefs.add(new TeacherDef(id, users.containsKey(id) ? users.get(id).getDisplayName()
                    : String.valueOf(id),
                    profile == null ? null : profile.getMaxWeeklyPeriods(),
                    profile == null ? null : profile.getMaxDailyPeriods(),
                    profile == null ? null : profile.getMaxDailySessions(),
                    profile == null ? null : profile.getMaxConsecutivePeriods()));
            teacherNames.put(id, List.of(users.containsKey(id) ? users.get(id).getDisplayName()
                    : String.valueOf(id)));
        }

        List<SchRoom> roomRows = roomMapper.selectList(new LambdaQueryWrapper<SchRoom>()
                .eq(SchRoom::getStatus, "ACTIVE").orderByAsc(SchRoom::getCode));
        List<RoomDef> rooms = roomRows.stream().map(r -> new RoomDef(r.getId(), r.getCode(), r.getCampusId(),
                r.getCapacity(), r.getRoomType(), splitSet(r.getFeatures()))).toList();

        List<ClassDef> classDefs = classIds.stream().map(id -> new ClassDef(id,
                classOrgs.containsKey(id) ? classOrgs.get(id).getName() : String.valueOf(id),
                0, classProfiles.containsKey(id) ? classProfiles.get(id).getMaxDailyPeriods() : null,
                classProfiles.containsKey(id) ? classProfiles.get(id).getMaxConsecutivePeriods() : null)).toList();

        List<TaskDef> taskDefs = new ArrayList<>();
        for (SchTask t : tasks) {
            SchCourse course = courses.get(t.getCourseId());
            List<SchTaskRoom> trs = taskRooms.getOrDefault(t.getId(), List.of());
            Set<Long> required = trs.stream().filter(r -> "REQUIRED".equals(r.getMode()))
                    .map(SchTaskRoom::getRoomId).collect(Collectors.toSet());
            Set<Long> preferred = trs.stream().filter(r -> "PREFERRED".equals(r.getMode()))
                    .map(SchTaskRoom::getRoomId).collect(Collectors.toSet());
            Set<Long> forbidden = trs.stream().filter(r -> "FORBIDDEN".equals(r.getMode()))
                    .map(SchTaskRoom::getRoomId).collect(Collectors.toSet());
            String roomType = t.getRoomType() != null ? t.getRoomType()
                    : (course == null ? null : course.getDefaultRoomType());
            Set<String> features = t.getRequiredFeatures() != null ? splitSet(t.getRequiredFeatures())
                    : (course == null || course.getRequiredFeatures() == null ? Set.of()
                    : splitSet(course.getRequiredFeatures()));
            int priority = t.getPriority() != null ? t.getPriority()
                    : (course == null || course.getPriority() == null ? 5 : course.getPriority());
            taskDefs.add(new TaskDef(t.getId(), t.getCode(), course == null ? t.getCode() : course.getName(),
                    t.getCourseId(), t.getTeachingClassId(),
                    taskTeachers.getOrDefault(t.getId(), List.of()), effectiveWeeks(ctx, t),
                    t.getSessionsPerWeek(), t.getPeriodsPerSession(), t.getTotalHours(),
                    !Boolean.FALSE.equals(t.getRoomRequired()), roomType, features, priority,
                    Boolean.TRUE.equals(t.getAllowSameDay()), Boolean.TRUE.equals(t.getUniformStart()),
                    required, preferred, forbidden));
        }

        List<TimeRule> rules = new ArrayList<>();
        for (SchTimeRule r : ruleService.rowsForProblem(req.semesterId())) {
            RuleTarget target;
            TimeRuleKind kind;
            try {
                target = RuleTarget.valueOf(r.getTargetType());
                kind = TimeRuleKind.valueOf(r.getRuleKind());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            long mask = Weeks.range(r.getStartWeek() == null ? 1 : r.getStartWeek(),
                    r.getEndWeek() == null ? ctx.config().getTeachingWeeks() : r.getEndWeek(), WeekParity.ALL);
            rules.add(new TimeRule(r.getId(), target, r.getTargetId(), kind, r.getDayOfWeek(),
                    r.getStartPeriod(), r.getEndPeriod(), mask, r.getWeight() == null ? 1 : r.getWeight()));
        }

        List<Placement> locked = new ArrayList<>();
        List<Placement> baseline = new ArrayList<>();
        if (req.baseVersionId() != null) {
            List<SchAssignment> existing = assignmentMapper.selectList(new LambdaQueryWrapper<SchAssignment>()
                    .eq(SchAssignment::getVersionId, req.baseVersionId())
                    .in(SchAssignment::getTaskId, taskIds));
            for (SchAssignment a : existing) {
                Placement placement = new Placement(a.getTaskId(), a.getSessionIndex(), a.getDayOfWeek(),
                        a.getStartPeriod(), a.getRoomId(), Boolean.TRUE.equals(a.getLocked()));
                baseline.add(placement);
                boolean replan = req.replanTaskIds() != null && req.replanTaskIds().contains(a.getTaskId());
                boolean lock = req.incremental() ? (placement.placed() && !replan)
                        : (req.keepLocked() && placement.placed() && Boolean.TRUE.equals(a.getLocked()) && !replan);
                if (lock) {
                    locked.add(placement);
                }
            }
        }

        Map<ConstraintCode, Integer> weights = termService.effectiveWeights(req.semesterId());
        if (req.weightOverrides() != null) {
            req.weightOverrides().forEach((code, value) -> {
                if (!code.hard() && value != null) {
                    weights.put(code, Math.max(0, value));
                }
            });
        }

        SchedulingProblem.Builder builder = SchedulingProblem.builder()
                .teachingWeeks(ctx.config().getTeachingWeeks())
                .teachingDays(new LinkedHashSet<>(ctx.teachingDays()))
                .periods(ctx.periods().stream().map(p -> new PeriodDef(p.getPeriodNo(), p.getSection(),
                        p.getStartTime().getHour() * 60 + p.getStartTime().getMinute(),
                        p.getEndTime().getHour() * 60 + p.getEndTime().getMinute())).toList())
                .nonTeachingWeeks(Weeks.all(ctx.config().getTeachingWeeks()) & ~ctx.teachableWeeks())
                .lostWeeksByDay(ctx.lostWeeksByDay())
                .extraWeeksByDay(ctx.extraWeeksByDay())
                .rooms(rooms)
                .teachers(teacherDefs)
                .classes(classDefs)
                .teachingClasses(new ArrayList<>(tcDefs.values()))
                .tasks(taskDefs)
                .rules(rules)
                .defaultTravelMinutes(ctx.config().getDefaultTravelMinutes())
                .maxRoomCandidates(ctx.config().getMaxRoomCandidates())
                .weights(weights)
                .locked(locked)
                .baseline(baseline);
        for (SchCampusTravel travel : travelMapper.selectList(null)) {
            builder.travel(travel.getFromCampusId(), travel.getToCampusId(), travel.getMinutes());
        }
        Map<Long, SchRoom> roomMap = roomRows.stream().collect(Collectors.toMap(SchRoom::getId, r -> r, (a, b) -> a));
        return new Loaded(builder.build(), ctx, tasks, courses, teachingClasses, classNames, teacherNames,
                roomMap);
    }

    private static int computeStudents(List<SchTeachingClassMember> members,
                                       Map<Long, SchClassProfile> profiles) {
        int sum = 0;
        for (SchTeachingClassMember m : members) {
            if (Boolean.TRUE.equals(m.getFullClass())) {
                SchClassProfile profile = profiles.get(m.getOrgUnitId());
                sum += profile == null || profile.getStudentCount() == null ? 0 : profile.getStudentCount();
            } else if (m.getStudentCount() != null) {
                sum += m.getStudentCount();
            }
        }
        return sum;
    }

    static Set<String> splitSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static Map<ConstraintCode, Integer> emptyWeights() {
        return new EnumMap<>(ConstraintCode.class);
    }

    static boolean sameTask(Placement a, Placement b) {
        return a.taskId() == b.taskId() && Objects.equals(a.sessionIndex(), b.sessionIndex());
    }

    static LocalDate dateOf(SchSemesterConfig config, int week, int dayOfWeek) {
        return TermCalendarService.mondayOf(config, week).plusDays(dayOfWeek - 1L);
    }
}
