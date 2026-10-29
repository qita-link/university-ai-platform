package com.campus.platform.scheduling.solver.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 排课问题的完整输入（与数据库、Spring 无关，可直接单元测试）。
 * <p>
 * 构造时预计算常用索引：节次、作息段、按对象分组的规则、教学班人数与成员、教师/班级的任务列表。
 * 求解器、复核器、评分器共用这一份数据，保证三者对同一份课表的口径一致。
 */
public final class SchedulingProblem {

    private final int teachingWeeks;
    private final List<Integer> teachingDays;
    private final Map<Integer, PeriodDef> periods;
    /** 可教学周（排除了 teaching_allowed=false 的特殊周） */
    private final long teachableWeeks;
    /** 按星期：因节假日/停课损失课次的周次（已扣除补课） */
    private final Map<Integer, Long> lostWeeksByDay;
    /** 按星期：调休日额外上课的周次（WORKDAY 按该星期课表上课） */
    private final Map<Integer, Long> extraWeeksByDay;
    private final Map<Long, RoomDef> rooms;
    private final Map<Long, TeacherDef> teachers;
    private final Map<Long, ClassDef> classes;
    private final Map<Long, TeachingClassDef> teachingClasses;
    private final List<TaskDef> tasks;
    private final Map<Long, TaskDef> taskById;
    private final List<TimeRule> rules;
    private final Map<Long, Integer> travelMinutes;
    private final int defaultTravelMinutes;
    private final Map<ConstraintCode, Integer> weights;
    private final int maxRoomCandidates;
    /** 锁定（或局部重排中被固定）的课次 */
    private final List<Placement> locked;
    /** 基准版本课次，用于 MINIMIZE_CHANGES 与求解提示 */
    private final List<Placement> baseline;

    private final Map<RuleTarget, Map<Long, List<TimeRule>>> rulesByTarget = new EnumMap<>(RuleTarget.class);

    private SchedulingProblem(Builder b) {
        this.teachingWeeks = b.teachingWeeks;
        this.teachingDays = List.copyOf(new TreeSet<>(b.teachingDays));
        this.periods = Collections.unmodifiableMap(b.periods.stream()
                .sorted((x, y) -> Integer.compare(x.no(), y.no()))
                .collect(Collectors.toMap(PeriodDef::no, Function.identity(), (x, y) -> x, LinkedHashMap::new)));
        this.teachableWeeks = Weeks.all(teachingWeeks) & ~b.nonTeachingWeeks;
        this.lostWeeksByDay = Map.copyOf(b.lostWeeksByDay);
        this.extraWeeksByDay = Map.copyOf(b.extraWeeksByDay);
        this.rooms = index(b.rooms, RoomDef::id);
        this.teachers = index(b.teachers, TeacherDef::id);
        this.classes = index(b.classes, ClassDef::id);
        this.teachingClasses = index(b.teachingClasses, TeachingClassDef::id);
        this.tasks = List.copyOf(b.tasks);
        this.taskById = index(b.tasks, TaskDef::id);
        this.rules = List.copyOf(b.rules);
        this.travelMinutes = Map.copyOf(b.travelMinutes);
        this.defaultTravelMinutes = b.defaultTravelMinutes;
        EnumMap<ConstraintCode, Integer> w = new EnumMap<>(ConstraintCode.class);
        for (ConstraintCode c : ConstraintCode.values()) {
            if (!c.hard()) {
                w.put(c, Math.max(0, b.weights.getOrDefault(c, c.defaultWeight())));
            }
        }
        this.weights = Collections.unmodifiableMap(w);
        this.maxRoomCandidates = b.maxRoomCandidates;
        this.locked = List.copyOf(b.locked);
        this.baseline = List.copyOf(b.baseline);
        for (TimeRule r : rules) {
            rulesByTarget.computeIfAbsent(r.target(), k -> new HashMap<>())
                    .computeIfAbsent(r.targetId() == null ? 0L : r.targetId(), k -> new ArrayList<>())
                    .add(r);
        }
    }

    private static <T> Map<Long, T> index(List<T> list, Function<T, Long> id) {
        Map<Long, T> m = new LinkedHashMap<>();
        for (T t : list) {
            m.put(id.apply(t), t);
        }
        return Collections.unmodifiableMap(m);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------- 基本访问

    public int teachingWeeks() {
        return teachingWeeks;
    }

    public List<Integer> teachingDays() {
        return teachingDays;
    }

    public Map<Integer, PeriodDef> periods() {
        return periods;
    }

    public long teachableWeeks() {
        return teachableWeeks;
    }

    public Map<Long, RoomDef> rooms() {
        return rooms;
    }

    public Map<Long, TeacherDef> teachers() {
        return teachers;
    }

    public Map<Long, ClassDef> classes() {
        return classes;
    }

    public Map<Long, TeachingClassDef> teachingClasses() {
        return teachingClasses;
    }

    public List<TaskDef> tasks() {
        return tasks;
    }

    public TaskDef task(long id) {
        return taskById.get(id);
    }

    public List<TimeRule> rules() {
        return rules;
    }

    public int weight(ConstraintCode code) {
        return weights.getOrDefault(code, 0);
    }

    public Map<ConstraintCode, Integer> weights() {
        return weights;
    }

    public int maxRoomCandidates() {
        return maxRoomCandidates;
    }

    public List<Placement> locked() {
        return locked;
    }

    public List<Placement> baseline() {
        return baseline;
    }

    // ---------------------------------------------------------------- 派生口径

    /** 任务实际上课周次 = 任务周次 ∩ 可教学周。 */
    public long effectiveWeeks(TaskDef t) {
        return t.weeksMask() & teachableWeeks;
    }

    /** 本任务按定义能提供的学时。 */
    public int providedHours(TaskDef t) {
        return t.periodsPerSession() * t.sessionsPerWeek() * Weeks.count(effectiveWeeks(t));
    }

    public int studentCount(TaskDef t) {
        TeachingClassDef tc = teachingClasses.get(t.teachingClassId());
        return tc == null ? 0 : tc.studentCount();
    }

    public List<TeachingClassDef.Member> members(TaskDef t) {
        TeachingClassDef tc = teachingClasses.get(t.teachingClassId());
        return tc == null ? List.of() : tc.members();
    }

    /** [start, start+len-1] 是否都是存在的节次、编号连续且同一作息段。 */
    public boolean validSpan(int start, int len) {
        PeriodDef first = periods.get(start);
        if (first == null || len < 1) {
            return false;
        }
        for (int p = start + 1; p < start + len; p++) {
            PeriodDef pd = periods.get(p);
            if (pd == null || !pd.section().equals(first.section())) {
                return false;
            }
        }
        return true;
    }

    public String section(int period) {
        PeriodDef pd = periods.get(period);
        return pd == null ? "" : pd.section();
    }

    /** 两节次之间（前一课次结束到后一课次开始）的间隔分钟数。 */
    public int gapMinutes(int endPeriod, int nextStartPeriod) {
        PeriodDef e = periods.get(endPeriod);
        PeriodDef s = periods.get(nextStartPeriod);
        if (e == null || s == null) {
            return Integer.MAX_VALUE;
        }
        return s.startMinute() - e.endMinute();
    }

    public int travelMinutes(long campusA, long campusB) {
        if (campusA == campusB) {
            return 0;
        }
        long lo = Math.min(campusA, campusB);
        long hi = Math.max(campusA, campusB);
        return travelMinutes.getOrDefault(travelKey(lo, hi), defaultTravelMinutes);
    }

    public static long travelKey(long lo, long hi) {
        return lo * 1_000_003L + hi;
    }

    public boolean multiCampus() {
        return rooms.values().stream().map(RoomDef::campusId).distinct().count() > 1;
    }

    public long lostWeeks(int day) {
        return lostWeeksByDay.getOrDefault(day, 0L);
    }

    public long extraWeeks(int day) {
        return extraWeeksByDay.getOrDefault(day, 0L);
    }

    public boolean hasCalendarLoss() {
        return lostWeeksByDay.values().stream().anyMatch(v -> v != 0L);
    }

    /** 某对象的全部规则；GLOBAL 用 targetId=0。 */
    public List<TimeRule> rulesOf(RuleTarget target, long targetId) {
        Map<Long, List<TimeRule>> m = rulesByTarget.get(target);
        if (m == null) {
            return List.of();
        }
        return m.getOrDefault(targetId, List.of());
    }

    // ---------------------------------------------------------------- Builder

    public static final class Builder {
        private int teachingWeeks = 16;
        private Set<Integer> teachingDays = Set.of(1, 2, 3, 4, 5);
        private List<PeriodDef> periods = new ArrayList<>();
        private long nonTeachingWeeks;
        private Map<Integer, Long> lostWeeksByDay = new HashMap<>();
        private Map<Integer, Long> extraWeeksByDay = new HashMap<>();
        private List<RoomDef> rooms = new ArrayList<>();
        private List<TeacherDef> teachers = new ArrayList<>();
        private List<ClassDef> classes = new ArrayList<>();
        private List<TeachingClassDef> teachingClasses = new ArrayList<>();
        private List<TaskDef> tasks = new ArrayList<>();
        private List<TimeRule> rules = new ArrayList<>();
        private Map<Long, Integer> travelMinutes = new HashMap<>();
        private int defaultTravelMinutes = 30;
        private Map<ConstraintCode, Integer> weights = new EnumMap<>(ConstraintCode.class);
        private int maxRoomCandidates = 12;
        private List<Placement> locked = new ArrayList<>();
        private List<Placement> baseline = new ArrayList<>();

        public Builder teachingWeeks(int v) { this.teachingWeeks = v; return this; }
        public Builder teachingDays(Set<Integer> v) { this.teachingDays = v; return this; }
        public Builder periods(List<PeriodDef> v) { this.periods = new ArrayList<>(v); return this; }
        public Builder nonTeachingWeeks(long v) { this.nonTeachingWeeks = v; return this; }
        public Builder lostWeeksByDay(Map<Integer, Long> v) { this.lostWeeksByDay = new HashMap<>(v); return this; }
        public Builder extraWeeksByDay(Map<Integer, Long> v) { this.extraWeeksByDay = new HashMap<>(v); return this; }
        public Builder rooms(List<RoomDef> v) { this.rooms = new ArrayList<>(v); return this; }
        public Builder teachers(List<TeacherDef> v) { this.teachers = new ArrayList<>(v); return this; }
        public Builder classes(List<ClassDef> v) { this.classes = new ArrayList<>(v); return this; }
        public Builder teachingClasses(List<TeachingClassDef> v) { this.teachingClasses = new ArrayList<>(v); return this; }
        public Builder tasks(List<TaskDef> v) { this.tasks = new ArrayList<>(v); return this; }
        public Builder rules(List<TimeRule> v) { this.rules = new ArrayList<>(v); return this; }
        public Builder travel(long campusA, long campusB, int minutes) {
            this.travelMinutes.put(travelKey(Math.min(campusA, campusB), Math.max(campusA, campusB)), minutes);
            return this;
        }
        public Builder defaultTravelMinutes(int v) { this.defaultTravelMinutes = v; return this; }
        public Builder weights(Map<ConstraintCode, Integer> v) { this.weights = new EnumMap<>(ConstraintCode.class); this.weights.putAll(v); return this; }
        public Builder weight(ConstraintCode c, int w) { this.weights.put(c, w); return this; }
        public Builder maxRoomCandidates(int v) { this.maxRoomCandidates = v; return this; }
        public Builder locked(List<Placement> v) { this.locked = new ArrayList<>(v); return this; }
        public Builder baseline(List<Placement> v) { this.baseline = new ArrayList<>(v); return this; }

        public SchedulingProblem build() {
            return new SchedulingProblem(this);
        }
    }
}
