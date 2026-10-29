package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ClassDef;
import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.Issue;
import com.campus.platform.scheduling.solver.model.PeriodDef;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.SolverParams;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.campus.platform.scheduling.solver.model.TeacherDef;
import com.campus.platform.scheduling.solver.model.TeachingClassDef;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@link SchedulingProblem} 编译成 CP-SAT 模型。
 * <p>
 * 变量：每个候选组合 (任务, 星期, 起始节, 教室) 一个布尔变量，以及少量辅助变量
 * （未安排次数、软约束的“是否使用”布尔量、超限量）。约束分四组构建：
 * 课次数量、时间资源冲突、资源上限（每日/每周/连续节数/跨校区）、软约束目标。
 * <p>
 * 所有“按周次”的约束只对 {@link ResourceGroups} 预先算出的极大并发任务集合建一次：
 * 某周实际活动集合若被极大集合包含，其负载必然不高于极大集合，因此约束仍然成立。
 */
public final class SchedulingModelBuilder {

    /** 每个未安排课次的目标惩罚，远大于任何软约束扣分，保证优先排满。 */
    public static final int UNSCHEDULED_PENALTY = 10_000;

    public record ModelStats(int candidates, int variables, int constraints, int tasks, long buildMillis) {
    }

    public record Built(CpModel model, List<Candidate> candidates, BoolVar[] vars,
                        List<CandidateGenerator.TaskCandidates> taskCandidates,
                        Map<Long, IntVar> unscheduled, List<Issue> issues, ModelStats stats) {
    }

    private SchedulingModelBuilder() {
    }

    public static Built build(SchedulingProblem p, List<CandidateGenerator.TaskCandidates> tcs,
                              SolverParams params) {
        long startNanos = System.nanoTime();
        CpModel model = new CpModel();
        List<Issue> issues = new ArrayList<>();

        List<Candidate> all = new ArrayList<>();
        for (CandidateGenerator.TaskCandidates tc : tcs) {
            all.addAll(tc.candidates());
        }
        BoolVar[] vars = new BoolVar[all.size()];
        for (int i = 0; i < all.size(); i++) {
            vars[i] = model.newBoolVar("x" + i);
        }

        // 任务 → 变量区间（候选按任务顺序连续生成）
        int[] rangeStart = new int[tcs.size()];
        int[] rangeEnd = new int[tcs.size()];
        int cursor = 0;
        for (int ti = 0; ti < tcs.size(); ti++) {
            rangeStart[ti] = cursor;
            cursor += tcs.get(ti).candidates().size();
            rangeEnd[ti] = cursor;
        }

        // 任务 → 时段 → 变量（覆盖该节次的候选）
        List<Map<Integer, List<BoolVar>>> taskSlotVars = new ArrayList<>();
        // 任务 → 星期 → 变量
        List<Map<Integer, List<BoolVar>>> taskDayVars = new ArrayList<>();
        for (int ti = 0; ti < tcs.size(); ti++) {
            Map<Integer, List<BoolVar>> bySlot = new HashMap<>();
            Map<Integer, List<BoolVar>> byDay = new HashMap<>();
            List<Candidate> cands = tcs.get(ti).candidates();
            for (int k = 0; k < cands.size(); k++) {
                Candidate c = cands.get(k);
                BoolVar v = vars[rangeStart[ti] + k];
                byDay.computeIfAbsent(c.day(), x -> new ArrayList<>()).add(v);
                for (int period = c.start(); period <= c.end(); period++) {
                    bySlot.computeIfAbsent(Candidate.slotKey(c.day(), period), x -> new ArrayList<>()).add(v);
                }
            }
            taskSlotVars.add(bySlot);
            taskDayVars.add(byDay);
        }

        Counter counter = new Counter();
        Map<Long, IntVar> unscheduled = new LinkedHashMap<>();
        applyLocked(p, tcs, rangeStart, vars, model, counter, issues);
        applySessionCount(p, tcs, rangeStart, rangeEnd, vars, model, counter, params, unscheduled, issues);
        applyTaskSlotUniqueness(tcs, rangeStart, vars, model, counter);
        applySameDay(tcs, rangeStart, vars, model, counter);
        applyUniformStart(tcs, rangeStart, vars, model, counter);
        applyRoomConflict(all, vars, model, counter);

        List<ResourceGroups.Group> groups = ResourceGroups.build(p, p.tasks());
        applyResourceConflict(p, groups, taskSlotVars, model, counter);
        applyLimits(p, groups, taskSlotVars, taskDayVars, model, counter);
        applyCampusTravel(p, groups, tcs, rangeStart, vars, model, counter);

        LinearExprBuilder objective = LinearExpr.newBuilder();
        for (int i = 0; i < all.size(); i++) {
            long cost = all.get(i).staticCost();
            if (cost != 0) {
                objective.addTerm(vars[i], cost);
            }
        }
        for (IntVar u : unscheduled.values()) {
            objective.addTerm(u, UNSCHEDULED_PENALTY);
        }
        applySpread(p, tcs, taskDayVars, model, counter, objective);
        applyRoomStability(p, tcs, rangeStart, vars, model, counter, objective);
        applyClassBalance(p, tcs, rangeStart, taskDayVars, model, counter, objective);
        applyTeacherCompact(p, tcs, rangeStart, taskDayVars, model, counter, objective);
        applyTeacherHalfDay(p, tcs, rangeStart, vars, model, counter, objective);

        model.minimize(objective.build());

        long millis = (System.nanoTime() - startNanos) / 1_000_000;
        ModelStats stats = new ModelStats(all.size(), model.model().getVariablesCount(),
                counter.value, tcs.size(), millis);
        return new Built(model, all, vars, tcs, unscheduled, issues, stats);
    }

    // ------------------------------------------------------------------ 硬约束

    /** 锁定的课次固定到原候选；找不到对应候选时报告，交由复核器判定 LOCKED 违反。 */
    private static void applyLocked(SchedulingProblem p, List<CandidateGenerator.TaskCandidates> tcs,
                                    int[] rangeStart, BoolVar[] vars, CpModel model, Counter counter,
                                    List<Issue> issues) {
        Map<Long, Integer> taskIndex = new HashMap<>();
        for (int ti = 0; ti < tcs.size(); ti++) {
            taskIndex.put(tcs.get(ti).task().id(), ti);
        }
        for (Placement lock : p.locked()) {
            Integer ti = taskIndex.get(lock.taskId());
            if (ti == null) {
                continue;
            }
            boolean matched = false;
            List<Candidate> cands = tcs.get(ti).candidates();
            for (int k = 0; k < cands.size(); k++) {
                Candidate c = cands.get(k);
                if (c.day() == lock.day() && c.start() == lock.startPeriod()
                        && java.util.Objects.equals(c.roomId(), lock.roomId())) {
                    model.addEquality(vars[rangeStart[ti] + k], 1);
                    counter.value++;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                issues.add(Issue.error(ConstraintCode.LOCKED.name(), lock.taskId(),
                        "锁定课次（" + Violation.slot(lock.day(), lock.startPeriod(), lock.startPeriod())
                                + "）不在可行候选内：教室不可用、时段被禁排或教室与任务要求不符，"
                                + "求解前请先解除锁定或修正数据"));
            }
        }
    }

    private static void applySessionCount(SchedulingProblem p, List<CandidateGenerator.TaskCandidates> tcs,
                                          int[] rangeStart, int[] rangeEnd, BoolVar[] vars, CpModel model,
                                          Counter counter, SolverParams params, Map<Long, IntVar> unscheduled,
                                          List<Issue> issues) {
        for (int ti = 0; ti < tcs.size(); ti++) {
            TaskDef t = tcs.get(ti).task();
            int n = rangeEnd[ti] - rangeStart[ti];
            int sessions = t.sessionsPerWeek();
            if (n == 0) {
                issues.add(Issue.error("NO_CANDIDATE", t.id(),
                        "教学任务 " + t.label() + " 没有可行候选：" + tcs.get(ti).explainEmpty()));
                continue;
            }
            BoolVar[] vs = Arrays.copyOfRange(vars, rangeStart[ti], rangeEnd[ti]);
            if (params.allowPartial()) {
                IntVar u = model.newIntVar(0, sessions, "u" + t.id());
                unscheduled.put(t.id(), u);
                model.addEquality(LinearExpr.newBuilder().addSum(vs).addTerm(u, -1).build(), sessions);
            } else {
                model.addEquality(LinearExpr.sum(vs), sessions);
            }
            counter.value++;
        }
    }

    /** 同一任务在 (星期, 起始节) 上最多一次（不同教室也不能同时上两门）。 */
    private static void applyTaskSlotUniqueness(List<CandidateGenerator.TaskCandidates> tcs, int[] rangeStart,
                                                BoolVar[] vars, CpModel model, Counter counter) {
        for (int ti = 0; ti < tcs.size(); ti++) {
            Map<Long, List<BoolVar>> bySlot = new LinkedHashMap<>();
            List<Candidate> cands = tcs.get(ti).candidates();
            for (int k = 0; k < cands.size(); k++) {
                Candidate c = cands.get(k);
                long key = (long) c.day() * 32 + c.start();
                bySlot.computeIfAbsent(key, x -> new ArrayList<>()).add(vars[rangeStart[ti] + k]);
            }
            for (List<BoolVar> vs : bySlot.values()) {
                if (vs.size() > 1) {
                    model.addLessOrEqual(LinearExpr.sum(vs.toArray(new BoolVar[0])), 1);
                    counter.value++;
                }
            }
        }
    }

    private static void applySameDay(List<CandidateGenerator.TaskCandidates> tcs, int[] rangeStart,
                                     BoolVar[] vars, CpModel model, Counter counter) {
        for (int ti = 0; ti < tcs.size(); ti++) {
            TaskDef t = tcs.get(ti).task();
            if (t.allowSameDay()) {
                continue;
            }
            Map<Integer, List<BoolVar>> byDay = new LinkedHashMap<>();
            List<Candidate> cands = tcs.get(ti).candidates();
            for (int k = 0; k < cands.size(); k++) {
                Candidate c = cands.get(k);
                byDay.computeIfAbsent(c.day(), x -> new ArrayList<>()).add(vars[rangeStart[ti] + k]);
            }
            for (List<BoolVar> vs : byDay.values()) {
                if (vs.size() > 1) {
                    model.addLessOrEqual(LinearExpr.sum(vs.toArray(new BoolVar[0])), 1);
                    counter.value++;
                }
            }
        }
    }

    /** 统一起始节：被使用的起始节最多一个。 */
    private static void applyUniformStart(List<CandidateGenerator.TaskCandidates> tcs, int[] rangeStart,
                                          BoolVar[] vars, CpModel model, Counter counter) {
        for (int ti = 0; ti < tcs.size(); ti++) {
            TaskDef t = tcs.get(ti).task();
            if (!t.uniformStart()) {
                continue;
            }
            Map<Integer, List<BoolVar>> byStart = new LinkedHashMap<>();
            List<Candidate> cands = tcs.get(ti).candidates();
            for (int k = 0; k < cands.size(); k++) {
                Candidate c = cands.get(k);
                byStart.computeIfAbsent(c.start(), x -> new ArrayList<>()).add(vars[rangeStart[ti] + k]);
            }
            if (byStart.size() <= 1) {
                continue;
            }
            List<BoolVar> used = new ArrayList<>();
            for (Map.Entry<Integer, List<BoolVar>> e : byStart.entrySet()) {
                BoolVar u = model.newBoolVar("start" + t.id() + "_" + e.getKey());
                for (BoolVar v : e.getValue()) {
                    model.addGreaterOrEqual(u, v);
                    counter.value++;
                }
                used.add(u);
            }
            model.addLessOrEqual(LinearExpr.sum(used.toArray(new BoolVar[0])), 1);
            counter.value++;
        }
    }

    private static void applyRoomConflict(List<Candidate> all, BoolVar[] vars, CpModel model, Counter counter) {
        Map<Long, List<BoolVar>> byRoomSlot = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            Candidate c = all.get(i);
            if (c.roomId() == null) {
                continue;
            }
            for (int period = c.start(); period <= c.end(); period++) {
                long key = c.roomId() * 10_000L + Candidate.slotKey(c.day(), period);
                byRoomSlot.computeIfAbsent(key, x -> new ArrayList<>()).add(vars[i]);
            }
        }
        for (List<BoolVar> vs : byRoomSlot.values()) {
            if (vs.size() > 1) {
                model.addLessOrEqual(LinearExpr.sum(vs.toArray(new BoolVar[0])), 1);
                counter.value++;
            }
        }
    }

    private static void applyResourceConflict(SchedulingProblem p, List<ResourceGroups.Group> groups,
                                              List<Map<Integer, List<BoolVar>>> taskSlotVars, CpModel model,
                                              Counter counter) {
        for (ResourceGroups.Group g : groups) {
            for (List<Integer> subset : g.weekMaximal()) {
                if (subset.size() < 2) {
                    continue;
                }
                Set<Integer> slots = new LinkedHashSet<>();
                for (Integer ti : subset) {
                    slots.addAll(taskSlotVars.get(ti).keySet());
                }
                for (Integer slot : slots) {
                    List<BoolVar> vs = new ArrayList<>();
                    for (Integer ti : subset) {
                        List<BoolVar> here = taskSlotVars.get(ti).get(slot);
                        if (here != null) {
                            vs.addAll(here);
                        }
                    }
                    if (vs.size() > 1) {
                        model.addLessOrEqual(LinearExpr.sum(vs.toArray(new BoolVar[0])), 1);
                        counter.value++;
                    }
                }
            }
        }
    }

    /** 教师/班级的每日节数、每日课次、连续节数、每周节数上限。 */
    private static void applyLimits(SchedulingProblem p, List<ResourceGroups.Group> groups,
                                    List<Map<Integer, List<BoolVar>>> taskSlotVars,
                                    List<Map<Integer, List<BoolVar>>> taskDayVars, CpModel model,
                                    Counter counter) {
        List<PeriodDef> periods = new ArrayList<>(p.periods().values());
        for (ResourceGroups.Group g : groups) {
            TeacherDef teacher = g.kind() == ResourceGroups.Kind.TEACHER ? p.teachers().get(g.resourceId()) : null;
            ClassDef cls = g.kind() == ResourceGroups.Kind.CLASS ? p.classes().get(g.resourceId()) : null;
            if (teacher == null && cls == null) {
                continue;
            }
            Integer maxDaily = teacher != null ? teacher.maxDailyPeriods() : cls.maxDailyPeriods();
            Integer maxDailySessions = teacher != null ? teacher.maxDailySessions() : null;
            Integer maxConsecutive = teacher != null ? teacher.maxConsecutivePeriods() : cls.maxConsecutivePeriods();
            Integer maxWeekly = teacher != null ? teacher.maxWeeklyPeriods() : null;

            for (List<Integer> subset : g.weekMaximal()) {
                if (subset.isEmpty()) {
                    continue;
                }
                if (maxDaily != null) {
                    for (int day : p.teachingDays()) {
                        LinearExprBuilder b = LinearExpr.newBuilder();
                        int terms = 0;
                        for (Integer ti : subset) {
                            List<BoolVar> vs = taskDayVars.get(ti).get(day);
                            if (vs == null) {
                                continue;
                            }
                            int len = p.tasks().get(ti).periodsPerSession();
                            for (BoolVar v : vs) {
                                b.addTerm(v, len);
                                terms++;
                            }
                        }
                        if (terms > 0) {
                            model.addLessOrEqual(b.build(), maxDaily);
                            counter.value++;
                        }
                    }
                }
                if (maxDailySessions != null) {
                    for (int day : p.teachingDays()) {
                        List<BoolVar> vs = new ArrayList<>();
                        for (Integer ti : subset) {
                            List<BoolVar> here = taskDayVars.get(ti).get(day);
                            if (here != null) {
                                vs.addAll(here);
                            }
                        }
                        if (!vs.isEmpty()) {
                            model.addLessOrEqual(LinearExpr.sum(vs.toArray(new BoolVar[0])), maxDailySessions);
                            counter.value++;
                        }
                    }
                }
                if (maxConsecutive != null && maxConsecutive < periods.size()) {
                    for (int day : p.teachingDays()) {
                        for (int[] window : windows(periods, maxConsecutive)) {
                            LinearExprBuilder b = LinearExpr.newBuilder();
                            int terms = 0;
                            for (Integer ti : subset) {
                                TaskDef t = p.tasks().get(ti);
                                for (int period = window[0]; period <= window[1]; period++) {
                                    List<BoolVar> vs = taskSlotVars.get(ti).get(Candidate.slotKey(day, period));
                                    if (vs == null) {
                                        continue;
                                    }
                                    // 每节课在该窗口内只能贡献 1 个节次（任务长度 >1 时在窗口内也只算覆盖到的节次）
                                    for (BoolVar v : vs) {
                                        b.addTerm(v, 1);
                                        terms++;
                                    }
                                }
                            }
                            if (terms > 0) {
                                model.addLessOrEqual(b.build(), maxConsecutive);
                                counter.value++;
                            }
                        }
                    }
                }
                if (maxWeekly != null) {
                    LinearExprBuilder b = LinearExpr.newBuilder();
                    int terms = 0;
                    for (Integer ti : subset) {
                        TaskDef t = p.tasks().get(ti);
                        for (Map.Entry<Integer, List<BoolVar>> e : taskDayVars.get(ti).entrySet()) {
                            for (BoolVar v : e.getValue()) {
                                b.addTerm(v, t.periodsPerSession());
                                terms++;
                            }
                        }
                    }
                    if (terms > 0) {
                        model.addLessOrEqual(b.build(), maxWeekly);
                        counter.value++;
                    }
                }
            }
        }
    }

    /** 同一作息段内长度 limit+1 的滑动窗口，用于连续上课上限。 */
    static List<int[]> windows(List<PeriodDef> periods, int limit) {
        List<int[]> out = new ArrayList<>();
        List<PeriodDef> sorted = new ArrayList<>(periods);
        sorted.sort(Comparator.comparingInt(PeriodDef::no));
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j + 1 < sorted.size()
                    && sorted.get(j + 1).no() == sorted.get(j).no() + 1
                    && sorted.get(j + 1).section().equals(sorted.get(i).section())) {
                j++;
            }
            for (int s = i; s + limit <= j; s++) {
                out.add(new int[]{sorted.get(s).no(), sorted.get(s + limit).no()});
            }
            i = j + 1;
        }
        return out;
    }

    /** 跨校区通行时间：间隔不足且分属不同校区时不能同时占用相邻时段。 */
    private static void applyCampusTravel(SchedulingProblem p, List<ResourceGroups.Group> groups,
                                          List<CandidateGenerator.TaskCandidates> tcs, int[] rangeStart,
                                          BoolVar[] vars, CpModel model, Counter counter) {
        if (!p.multiCampus()) {
            return;
        }
        // 每个任务：星期 → 结束节 → 校区 → 变量
        List<Map<Integer, Map<Integer, Map<Long, List<BoolVar>>>>> endsByTask = new ArrayList<>();
        List<Map<Integer, Map<Integer, Map<Long, List<BoolVar>>>>> startsByTask = new ArrayList<>();
        for (int ti = 0; ti < tcs.size(); ti++) {
            Map<Integer, Map<Integer, Map<Long, List<BoolVar>>>> byEnd = new HashMap<>();
            Map<Integer, Map<Integer, Map<Long, List<BoolVar>>>> byStart = new HashMap<>();
            List<Candidate> cands = tcs.get(ti).candidates();
            for (int k = 0; k < cands.size(); k++) {
                Candidate c = cands.get(k);
                if (c.roomId() == null) {
                    continue;
                }
                long campus = p.rooms().get(c.roomId()) == null ? 0L : p.rooms().get(c.roomId()).campusId();
                if (campus == 0L) {
                    continue;
                }
                BoolVar v = vars[rangeStart[ti] + k];
                byEnd.computeIfAbsent(c.day(), x -> new HashMap<>())
                        .computeIfAbsent(c.end(), x -> new HashMap<>())
                        .computeIfAbsent(campus, x -> new ArrayList<>()).add(v);
                byStart.computeIfAbsent(c.day(), x -> new HashMap<>())
                        .computeIfAbsent(c.start(), x -> new HashMap<>())
                        .computeIfAbsent(campus, x -> new ArrayList<>()).add(v);
            }
            endsByTask.add(byEnd);
            startsByTask.add(byStart);
        }
        Set<Long> campuses = new LinkedHashSet<>();
        p.rooms().values().forEach(r -> campuses.add(r.campusId()));
        for (ResourceGroups.Group g : groups) {
            for (List<Integer> subset : g.weekMaximal()) {
                if (subset.size() < 2) {
                    continue;
                }
                for (int day : p.teachingDays()) {
                    for (Integer a : subset) {
                        Map<Integer, Map<Long, List<BoolVar>>> endsA = endsByTask.get(a).get(day);
                        if (endsA == null) {
                            continue;
                        }
                        for (Map.Entry<Integer, Map<Long, List<BoolVar>>> e : endsA.entrySet()) {
                            int endPeriod = e.getKey();
                            for (Integer b : subset) {
                                Map<Integer, Map<Long, List<BoolVar>>> startsB = startsByTask.get(b).get(day);
                                if (startsB == null) {
                                    continue;
                                }
                                for (Map.Entry<Integer, Map<Long, List<BoolVar>>> s : startsB.entrySet()) {
                                    int startPeriod = s.getKey();
                                    if (startPeriod <= endPeriod) {
                                        continue;
                                    }
                                    for (Long ca : e.getValue().keySet()) {
                                        for (Long cb : s.getValue().keySet()) {
                                            if (ca.equals(cb)) {
                                                continue;
                                            }
                                            int gap = p.gapMinutes(endPeriod, startPeriod);
                                            if (gap >= p.travelMinutes(ca, cb)) {
                                                continue;
                                            }
                                            List<BoolVar> vs = new ArrayList<>(e.getValue().get(ca));
                                            vs.addAll(s.getValue().get(cb));
                                            if (vs.size() > 1) {
                                                model.addLessOrEqual(LinearExpr.sum(vs.toArray(new BoolVar[0])), 1);
                                                counter.value++;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 软约束

    /** 同一任务两次课排在相邻两天。 */
    private static void applySpread(SchedulingProblem p, List<CandidateGenerator.TaskCandidates> tcs,
                                    List<Map<Integer, List<BoolVar>>> taskDayVars, CpModel model,
                                    Counter counter, LinearExprBuilder objective) {
        int w = p.weight(ConstraintCode.SESSION_SPREAD);
        if (w <= 0) {
            return;
        }
        List<Integer> days = p.teachingDays();
        for (int ti = 0; ti < tcs.size(); ti++) {
            TaskDef t = tcs.get(ti).task();
            if (t.sessionsPerWeek() < 2) {
                continue;
            }
            for (int i = 0; i + 1 < days.size(); i++) {
                if (days.get(i + 1) != days.get(i) + 1) {
                    continue;
                }
                List<BoolVar> a = taskDayVars.get(ti).get(days.get(i));
                List<BoolVar> b = taskDayVars.get(ti).get(days.get(i + 1));
                if (a == null || b == null) {
                    continue;
                }
                BoolVar s = model.newBoolVar("spread" + t.id() + "_" + days.get(i));
                model.addGreaterOrEqual(s, LinearExpr.newBuilder()
                        .addSum(a.toArray(new BoolVar[0]))
                        .addSum(b.toArray(new BoolVar[0]))
                        .add(-1).build());
                counter.value++;
                objective.addTerm(s, w);
            }
        }
    }

    /** 同一任务使用多个教室的惩罚。 */
    private static void applyRoomStability(SchedulingProblem p, List<CandidateGenerator.TaskCandidates> tcs,
                                           int[] rangeStart, BoolVar[] vars, CpModel model, Counter counter,
                                           LinearExprBuilder objective) {
        int w = p.weight(ConstraintCode.ROOM_STABILITY);
        if (w <= 0) {
            return;
        }
        for (int ti = 0; ti < tcs.size(); ti++) {
            TaskDef t = tcs.get(ti).task();
            if (t.sessionsPerWeek() < 2) {
                continue;
            }
            Map<Long, List<BoolVar>> byRoom = new LinkedHashMap<>();
            List<Candidate> cands = tcs.get(ti).candidates();
            for (int k = 0; k < cands.size(); k++) {
                Long room = cands.get(k).roomId();
                if (room == null) {
                    continue;
                }
                byRoom.computeIfAbsent(room, x -> new ArrayList<>()).add(vars[rangeStart[ti] + k]);
            }
            if (byRoom.size() <= 1) {
                continue;
            }
            List<BoolVar> used = new ArrayList<>();
            for (Map.Entry<Long, List<BoolVar>> e : byRoom.entrySet()) {
                BoolVar u = model.newBoolVar("room" + t.id() + "_" + e.getKey());
                for (BoolVar v : e.getValue()) {
                    model.addGreaterOrEqual(u, v);
                    counter.value++;
                }
                used.add(u);
            }
            IntVar pen = model.newIntVar(0, Math.max(1, t.sessionsPerWeek() - 1), "roompen" + t.id());
            model.addGreaterOrEqual(pen, LinearExpr.newBuilder()
                    .addSum(used.toArray(new BoolVar[0])).add(-1).build());
            counter.value++;
            objective.addTerm(pen, w);
        }
    }

    /** 班级每日课量超出平均值部分。 */
    private static void applyClassBalance(SchedulingProblem p, List<CandidateGenerator.TaskCandidates> tcs,
                                          int[] rangeStart, List<Map<Integer, List<BoolVar>>> taskDayVars,
                                          CpModel model, Counter counter, LinearExprBuilder objective) {
        int w = p.weight(ConstraintCode.CLASS_DAILY_BALANCE);
        if (w <= 0) {
            return;
        }
        List<Integer> days = p.teachingDays();
        if (days.isEmpty()) {
            return;
        }
        Map<Long, List<Integer>> fullTasksByClass = new LinkedHashMap<>();
        for (int ti = 0; ti < tcs.size(); ti++) {
            for (TeachingClassDef.Member m : p.members(tcs.get(ti).task())) {
                if (m.fullClass()) {
                    fullTasksByClass.computeIfAbsent(m.classId(), x -> new ArrayList<>()).add(ti);
                }
            }
        }
        for (Map.Entry<Long, List<Integer>> e : fullTasksByClass.entrySet()) {
            int totalSessions = 0;
            for (Integer ti : e.getValue()) {
                totalSessions += tcs.get(ti).task().sessionsPerWeek();
            }
            if (totalSessions == 0) {
                continue;
            }
            // 均衡口径按“课次数”而非节数：每周 N 次课理想是分散到 ceil(N/天数) 次/天
            int avg = (int) Math.ceil((double) totalSessions / days.size());
            for (int day : days) {
                LinearExprBuilder load = LinearExpr.newBuilder();
                int terms = 0;
                for (Integer ti : e.getValue()) {
                    List<BoolVar> vs = taskDayVars.get(ti).get(day);
                    if (vs == null) {
                        continue;
                    }
                    for (BoolVar v : vs) {
                        load.addTerm(v, 1);
                        terms++;
                    }
                }
                if (terms == 0) {
                    continue;
                }
                IntVar excess = model.newIntVar(0, totalSessions, "bal" + e.getKey() + "_" + day);
                model.addGreaterOrEqual(excess, LinearExpr.newBuilder().add(load.build()).add(-avg).build());
                counter.value++;
                objective.addTerm(excess, w);
            }
        }
    }

    /** 教师到校天数：每天是否被使用，尽量减少。 */
    private static void applyTeacherCompact(SchedulingProblem p, List<CandidateGenerator.TaskCandidates> tcs,
                                            int[] rangeStart, List<Map<Integer, List<BoolVar>>> taskDayVars,
                                            CpModel model, Counter counter, LinearExprBuilder objective) {
        int w = p.weight(ConstraintCode.TEACHER_COMPACT);
        if (w <= 0) {
            return;
        }
        Map<Long, List<Integer>> tasksByTeacher = new LinkedHashMap<>();
        for (int ti = 0; ti < tcs.size(); ti++) {
            for (Long teacher : tcs.get(ti).task().teacherIds()) {
                tasksByTeacher.computeIfAbsent(teacher, x -> new ArrayList<>()).add(ti);
            }
        }
        for (Map.Entry<Long, List<Integer>> e : tasksByTeacher.entrySet()) {
            for (int day : p.teachingDays()) {
                List<BoolVar> vs = new ArrayList<>();
                for (Integer ti : e.getValue()) {
                    List<BoolVar> here = taskDayVars.get(ti).get(day);
                    if (here != null) {
                        vs.addAll(here);
                    }
                }
                if (vs.isEmpty()) {
                    continue;
                }
                BoolVar used = model.newBoolVar("tday" + e.getKey() + "_" + day);
                for (BoolVar v : vs) {
                    model.addGreaterOrEqual(used, v);
                    counter.value++;
                }
                objective.addTerm(used, w);
            }
        }
    }

    /**
     * 教师半天集中：同一教师的课尽量落在同一个「半天」（上午/下午/晚上，取自节次作息段）。
     * <p>
     * 与「到校天数紧凑」是两个维度：那个减少「来几天」，这个减少「一天里跑几趟」。
     * 外聘教师只能来一天时，把两个权重调高即可把课压进同一个半天。
     */
    private static void applyTeacherHalfDay(SchedulingProblem p, List<CandidateGenerator.TaskCandidates> tcs,
                                            int[] rangeStart, BoolVar[] vars, CpModel model, Counter counter,
                                            LinearExprBuilder objective) {
        int w = p.weight(ConstraintCode.TEACHER_HALF_DAY);
        if (w <= 0) {
            return;
        }
        // 教师 -> (星期|作息段) -> 落在该半天的候选变量
        Map<Long, Map<String, List<BoolVar>>> slotsByTeacher = new LinkedHashMap<>();
        for (int ti = 0; ti < tcs.size(); ti++) {
            List<Candidate> cands = tcs.get(ti).candidates();
            for (Long teacher : tcs.get(ti).task().teacherIds()) {
                Map<String, List<BoolVar>> slots = slotsByTeacher.computeIfAbsent(teacher,
                        x -> new LinkedHashMap<>());
                for (int k = 0; k < cands.size(); k++) {
                    Candidate c = cands.get(k);
                    String key = c.day() + "|" + sectionOf(p, c.start());
                    slots.computeIfAbsent(key, x -> new ArrayList<>()).add(vars[rangeStart[ti] + k]);
                }
            }
        }
        for (Map.Entry<Long, Map<String, List<BoolVar>>> e : slotsByTeacher.entrySet()) {
            for (Map.Entry<String, List<BoolVar>> s : e.getValue().entrySet()) {
                if (s.getValue().isEmpty()) {
                    continue;
                }
                BoolVar used = model.newBoolVar("thalf" + e.getKey() + "_" + s.getKey().replace('|', '_'));
                for (BoolVar v : s.getValue()) {
                    model.addGreaterOrEqual(used, v);
                    counter.value++;
                }
                objective.addTerm(used, w);
            }
        }
    }

    /** 节次所属作息段（MORNING/AFTERNOON/EVENING）；取不到时归为 UNKNOWN，不与别的段混在一起。 */
    private static String sectionOf(SchedulingProblem p, int period) {
        String section = p.section(period);
        return section == null || section.isBlank() ? "UNKNOWN" : section;
    }

    private static final class Counter {
        int value;
    }
    /** 供外部诊断使用：模块内可见的极大并发集合。 */
    public static List<ResourceGroups.Group> groups(SchedulingProblem p) {
        return ResourceGroups.build(p, p.tasks());
    }
}
