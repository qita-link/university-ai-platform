package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ClassDef;
import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.Issue;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.RoomDef;
import com.campus.platform.scheduling.solver.model.RuleTarget;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.campus.platform.scheduling.solver.model.TeacherDef;
import com.campus.platform.scheduling.solver.model.TimeRule;
import com.campus.platform.scheduling.solver.model.TimeRuleKind;
import com.campus.platform.scheduling.solver.model.Weeks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 求解前预检与求解后诊断。
 * <p>
 * CP-SAT 证明 INFEASIBLE 时不会告诉我们是哪条业务规则导致无解，因此：
 * <ol>
 *   <li>预检在建模前找出可以确定的数据问题（学时不一致、候选为空、工作量超限等）；</li>
 *   <li>求解后对每个未排课次，逐一试放到候选上，统计被教师/班级/教室冲突挡住的数量；</li>
 *   <li>无解时给出资源紧张程度的量化提示，而不是笼统的“排课失败”。</li>
 * </ol>
 */
public final class SchedulingPrecheck {

    private SchedulingPrecheck() {
    }

    public static List<Issue> check(SchedulingProblem p) {
        List<Issue> issues = new ArrayList<>();
        List<CandidateGenerator.TaskCandidates> tcs = CandidateGenerator.generate(p, p.tasks(), Map.of());
        Map<Long, Integer> sessionsByTeacher = new LinkedHashMap<>();

        for (CandidateGenerator.TaskCandidates tc : tcs) {
            TaskDef t = tc.task();
            int provided = p.providedHours(t);
            if (provided != t.totalHours()) {
                issues.add(Issue.error(ConstraintCode.HOURS_CONSISTENCY.name(), t.id(),
                        "教学任务 " + t.label() + " 学时不一致：" + t.periodsPerSession() + " 节/次 × "
                                + t.sessionsPerWeek() + " 次/周 × 有效教学周 "
                                + Weeks.count(p.effectiveWeeks(t)) + " 周 = " + provided
                                + " 学时，任务要求 " + t.totalHours() + " 学时（有效周次："
                                + Weeks.format(p.effectiveWeeks(t)) + "）"));
            }
            if (!t.allowSameDay() && t.sessionsPerWeek() > p.teachingDays().size()) {
                issues.add(Issue.error(ConstraintCode.SAME_DAY.name(), t.id(),
                        "教学任务 " + t.label() + " 每周 " + t.sessionsPerWeek() + " 次课，但每周只有 "
                                + p.teachingDays().size() + " 个教学日且不允许同一天多次"));
            }
            if (tc.candidates().isEmpty()) {
                issues.add(Issue.error("NO_CANDIDATE", t.id(),
                        "教学任务 " + t.label() + " 没有可行候选：" + tc.explainEmpty()));
            }
            for (Long teacherId : new LinkedHashSet<>(t.teacherIds())) {
                sessionsByTeacher.merge(teacherId, t.sessionsPerWeek() * t.periodsPerSession(), Integer::sum);
            }
            checkTotalSlots(p, t, issues);
        }

        for (Map.Entry<Long, Integer> e : sessionsByTeacher.entrySet()) {
            TeacherDef teacher = p.teachers().get(e.getKey());
            if (teacher == null) {
                continue;
            }
            if (teacher.maxWeeklyPeriods() != null && e.getValue() > teacher.maxWeeklyPeriods()) {
                issues.add(Issue.error(ConstraintCode.TEACHER_WEEKLY_MAX.name(), null,
                        "教师 " + teacher.name() + " 承担 " + e.getValue() + " 节/周，超过档案上限 "
                                + teacher.maxWeeklyPeriods() + " 节/周"));
            }
            if (allSlotsForbidden(p, teacher.id())) {
                issues.add(Issue.error(ConstraintCode.TEACHER_UNAVAILABLE.name(), null,
                        "教师 " + teacher.name() + " 的禁排规则覆盖了所有教学日与节次，无法安排任何课程"));
            }
            if (availabilityBlocksAll(p, teacher.id())) {
                issues.add(Issue.error(ConstraintCode.TEACHER_AVAILABILITY.name(), null,
                        "教师 " + teacher.name() + " 的可来时间窗与教学日/节次没有交集，无法安排任何课程"));
            }
        }

        for (Placement lock : p.locked()) {
            TaskDef t = p.task(lock.taskId());
            if (t == null) {
                continue;
            }
            boolean found = tcs.stream().filter(tc -> tc.task().id() == t.id())
                    .flatMap(tc -> tc.candidates().stream())
                    .anyMatch(c -> c.day() == lock.day() && c.start() == lock.startPeriod()
                            && Objects.equals(c.roomId(), lock.roomId()));
            if (!found) {
                issues.add(Issue.error(ConstraintCode.LOCKED.name(), t.id(),
                        "锁定课次（" + Violation.slot(lock.day(), lock.startPeriod(), lock.startPeriod())
                                + "）已不在可行候选内（时段禁排、教室维护或教室不匹配）"));
            }
        }
        return issues;
    }

    /** 每个任务的需求学时是否超过它可用的时间槽（按教学班/教师最紧的资源估算）。 */
    private static void checkTotalSlots(SchedulingProblem p, TaskDef t, List<Issue> issues) {
        int slots = p.teachingDays().size() * p.periods().size();
        int need = t.sessionsPerWeek() * t.periodsPerSession();
        if (need > slots) {
            issues.add(Issue.error(ConstraintCode.TEACHING_TIME.name(), t.id(),
                    "教学任务 " + t.label() + " 每周需要 " + need + " 节，但全周只有 " + slots + " 个节次"));
        }
        ClassDef cls = null;
        for (var m : p.members(t)) {
            if (m.fullClass()) {
                cls = p.classes().get(m.classId());
                break;
            }
        }
        if (cls != null && cls.maxDailyPeriods() != null) {
            int maxWeek = cls.maxDailyPeriods() * p.teachingDays().size();
            if (need > maxWeek) {
                issues.add(Issue.error(ConstraintCode.CLASS_DAILY_MAX.name(), t.id(),
                        "班级 " + cls.name() + " 每天最多 " + cls.maxDailyPeriods() + " 节，本任务每周需要 "
                                + need + " 节，与其课程合并后可能超过上限"));
            }
        }
    }

    private static boolean allSlotsForbidden(SchedulingProblem p, long teacherId) {
        List<TimeRule> rules = p.rulesOf(RuleTarget.TEACHER, teacherId);
        if (rules.isEmpty()) {
            return false;
        }
        for (int day : p.teachingDays()) {
            for (int period : p.periods().keySet()) {
                boolean blocked = rules.stream().anyMatch(r -> r.kind() == TimeRuleKind.FORBIDDEN
                        && r.overlaps(day, period, period));
                if (!blocked) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 可来时间窗（AVAILABLE）与所有教学日/节次都没有交集：
     * 外聘教师被设置成「只能周三来」但教学日里没有周三之类，先在这里拦住，比让求解器交白卷更好排查。
     */
    private static boolean availabilityBlocksAll(SchedulingProblem p, long teacherId) {
        List<TimeRule> available = p.rulesOf(RuleTarget.TEACHER, teacherId).stream()
                .filter(r -> r.kind() == TimeRuleKind.AVAILABLE).toList();
        if (available.isEmpty()) {
            return false;
        }
        for (int day : p.teachingDays()) {
            for (int period : p.periods().keySet()) {
                boolean ok = available.stream().anyMatch(r -> r.contains(day, period, period));
                if (ok) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 求解后：为每个未安排的课次给出候选被阻挡的统计。
     */
    public static List<Issue> explainUnscheduled(SchedulingProblem p, List<Placement> placements) {
        List<Issue> issues = new ArrayList<>();
        Map<Long, List<Placement>> placedByTask = new LinkedHashMap<>();
        for (Placement pl : placements) {
            if (pl.placed()) {
                placedByTask.computeIfAbsent(pl.taskId(), k -> new ArrayList<>()).add(pl);
            }
        }
        List<Placement> placed = placements.stream().filter(Placement::placed).toList();
        for (TaskDef t : p.tasks()) {
            List<Placement> own = placedByTask.getOrDefault(t.id(), List.of());
            int missing = t.sessionsPerWeek() - own.size();
            if (missing <= 0) {
                continue;
            }
            CandidateGenerator.TaskCandidates tc = CandidateGenerator.forTask(p, t, 0, 0,
                    own);
            if (tc.candidates().isEmpty()) {
                issues.add(Issue.error("UNSCHEDULED", t.id(), "教学任务 " + t.label() + " 未安排 " + missing
                        + " 次课：" + tc.explainEmpty()));
                continue;
            }
            int teacherBlocked = 0;
            int classBlocked = 0;
            int roomBlocked = 0;
            int free = 0;
            for (Candidate c : tc.candidates()) {
                int end = c.end();
                boolean tBlock = false;
                boolean cBlock = false;
                boolean rBlock = false;
                for (Placement pl : placed) {
                    if (pl.taskId() == t.id()) {
                        continue;
                    }
                    TaskDef other = p.task(pl.taskId());
                    if (other == null || (p.effectiveWeeks(t) & p.effectiveWeeks(other)) == 0) {
                        continue;
                    }
                    int otherEnd = pl.startPeriod() + other.periodsPerSession() - 1;
                    boolean overlap = pl.day() == c.day() && pl.startPeriod() <= end && c.start() <= otherEnd;
                    if (!overlap) {
                        continue;
                    }
                    ConstraintCode code = ResourceGroups.conflict(p, t, other);
                    if (code == ConstraintCode.TEACHER_CONFLICT) {
                        tBlock = true;
                    } else if (code == ConstraintCode.CLASS_CONFLICT) {
                        cBlock = true;
                    }
                    if (c.roomId() != null && c.roomId().equals(pl.roomId())) {
                        rBlock = true;
                    }
                }
                if (tBlock) {
                    teacherBlocked++;
                } else if (cBlock) {
                    classBlocked++;
                } else if (rBlock) {
                    roomBlocked++;
                } else {
                    free++;
                }
            }
            String hint = free > 0
                    ? "另有 " + free + " 个候选在求解时未被使用（可能受时间上限、连续节数或其它上限约束影响）"
                    : "所有候选都被占用，需要增加时段、教室或调整教师/班级的其他课程";
            issues.add(Issue.warning("UNSCHEDULED", t.id(), "教学任务 " + t.label() + " 未安排 " + missing
                    + " 次课：候选 " + tc.candidates().size() + " 个，其中教师冲突 " + teacherBlocked
                    + "、班级冲突 " + classBlocked + "、教室冲突 " + roomBlocked + "；" + hint));
        }
        return issues;
    }

    /** 无解时的资源紧张度提示。 */
    public static List<Issue> infeasibleHints(SchedulingProblem p) {
        List<Issue> issues = new ArrayList<>();
        int days = p.teachingDays().size();
        int periods = p.periods().size();
        Map<Long, Integer> teacherNeed = new LinkedHashMap<>();
        Map<Long, Integer> classNeed = new LinkedHashMap<>();
        int totalNeed = 0;
        for (TaskDef t : p.tasks()) {
            int need = t.sessionsPerWeek() * t.periodsPerSession();
            totalNeed += need;
            for (Long teacherId : t.teacherIds()) {
                teacherNeed.merge(teacherId, need, Integer::sum);
            }
            for (var m : p.members(t)) {
                classNeed.merge(m.classId(), need, Integer::sum);
            }
        }
        for (Map.Entry<Long, Integer> e : classNeed.entrySet()) {
            ClassDef cls = p.classes().get(e.getKey());
            if (cls == null) {
                continue;
            }
            int capacity = days * periods;
            if (e.getValue() > capacity) {
                issues.add(Issue.error("CLASS_CAPACITY", null,
                        "班级 " + cls.name() + " 的课程每周共需 " + e.getValue() + " 节，超过可用时段总数 "
                                + capacity + " 节（" + days + " 天 × " + periods + " 节）"));
            } else if (e.getValue() > capacity * 0.9) {
                issues.add(Issue.warning("CLASS_TIGHT", null,
                        "班级 " + cls.name() + " 每周 " + e.getValue() + " 节，已占用可用时段的 "
                                + Math.round(100.0 * e.getValue() / capacity) + "%，排课空间很紧"));
            }
        }
        for (Map.Entry<Long, Integer> e : teacherNeed.entrySet()) {
            TeacherDef teacher = p.teachers().get(e.getKey());
            if (teacher == null) {
                continue;
            }
            long available = availableSlots(p, teacher.id());
            if (available > 0 && e.getValue() > available) {
                issues.add(Issue.error("TEACHER_CAPACITY", null,
                        "教师 " + teacher.name() + " 每周需上 " + e.getValue() + " 节，但可用时段只有 " + available
                                + " 个（已扣除禁排时间）"));
            }
        }
        List<String> labCourses = new ArrayList<>();
        for (TaskDef t : p.tasks()) {
            if (t.roomRequired() && t.roomType() != null && !t.roomType().equals("NORMAL")
                    && !t.roomType().equals("MULTIMEDIA")) {
                long rooms = p.rooms().values().stream().filter(r -> t.roomType().equals(r.roomType())).count();
                if (rooms == 0) {
                    labCourses.add(t.label() + "（需要 " + t.roomType() + "）");
                }
            }
        }
        if (!labCourses.isEmpty()) {
            issues.add(Issue.error("ROOM_TYPE_MISSING", null,
                    "以下课程要求的教室类型在启用教室中不存在：" + String.join("、", labCourses)));
        }
        issues.add(Issue.info("RESOURCE_SUMMARY", null,
                "本次排课共 " + p.tasks().size() + " 个教学任务、每周 " + totalNeed + " 节；可用 " + days + " 天 × "
                        + periods + " 节 = " + (days * periods) + " 个时段；教室 " + p.rooms().size() + " 间"));
        return issues;
    }

    private static long availableSlots(SchedulingProblem p, long teacherId) {
        List<TimeRule> rules = p.rulesOf(RuleTarget.TEACHER, teacherId);
        long count = 0;
        for (int day : p.teachingDays()) {
            for (int period : p.periods().keySet()) {
                int finalDay = day;
                int finalPeriod = period;
                boolean blocked = rules.stream().anyMatch(r -> r.kind() == TimeRuleKind.FORBIDDEN
                        && r.overlaps(finalDay, finalPeriod, finalPeriod));
                if (!blocked) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 教室容量总体情况（供诊断展示）。 */
    public static String roomCapacitySummary(SchedulingProblem p) {
        int max = p.rooms().values().stream().mapToInt(RoomDef::capacity).max().orElse(0);
        int largestClass = p.teachingClasses().values().stream().mapToInt(tc -> tc.studentCount()).max().orElse(0);
        return "启用教室 " + p.rooms().size() + " 间，最大容量 " + max + "，最大教学班人数 " + largestClass;
    }

    static List<Placement> sorted(List<Placement> placements) {
        List<Placement> copy = new ArrayList<>(placements);
        copy.sort(Comparator.comparingLong(Placement::taskId).thenComparingInt(Placement::sessionIndex));
        return copy;
    }

    static Set<Long> teacherIds(SchedulingProblem p, TaskDef t) {
        return new LinkedHashSet<>(t.teacherIds());
    }
}
