package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ClassDef;
import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.Issue;
import com.campus.platform.scheduling.solver.model.PeriodDef;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.RoomDef;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.campus.platform.scheduling.solver.model.TeacherDef;
import com.campus.platform.scheduling.solver.model.TeachingClassDef;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 独立硬约束复核器。
 * <p>
 * 不使用求解模型，只按业务规则逐条检查课表，因此求解器建模错误不会静默进入发布流程。
 * 求解结果、人工调整、发布前都调用这里。
 */
@Component
public final class SchedulingValidator {

    public record Report(List<Violation> violations, List<Issue> issues) {

        public boolean feasible() {
            return violations.isEmpty();
        }

        public boolean hasCode(ConstraintCode code) {
            return violations.stream().anyMatch(v -> v.code() == code);
        }

        public Map<ConstraintCode, Long> countByCode() {
            Map<ConstraintCode, Long> m = new LinkedHashMap<>();
            for (Violation v : violations) {
                m.merge(v.code(), 1L, Long::sum);
            }
            return m;
        }
    }

    public Report validate(SchedulingProblem p, Collection<Placement> placements) {
        List<Violation> violations = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();

        Map<Long, TaskDef> tasks = new LinkedHashMap<>();
        for (TaskDef t : p.tasks()) {
            tasks.put(t.id(), t);
        }

        // 每个任务的课次
        Map<Long, List<Placement>> byTask = new LinkedHashMap<>();
        for (Placement pl : placements) {
            TaskDef t = tasks.get(pl.taskId());
            if (t == null) {
                issues.add(Issue.warning("UNKNOWN_TASK", pl.taskId(),
                        "课表引用了不在本次排课范围内的教学任务 " + pl.taskId()));
                continue;
            }
            if (pl.sessionIndex() < 1 || pl.sessionIndex() > t.sessionsPerWeek()) {
                violations.add(new Violation(ConstraintCode.SESSION_COUNT, t.id(), pl.sessionIndex(), null, null,
                        "教学任务 " + t.label() + " 的课次号 " + pl.sessionIndex()
                                + " 超出每周次数 " + t.sessionsPerWeek()));
                continue;
            }
            byTask.computeIfAbsent(t.id(), k -> new ArrayList<>()).add(pl);
        }

        for (TaskDef t : p.tasks()) {
            List<Placement> list = byTask.getOrDefault(t.id(), List.of());
            checkTask(p, t, list, violations, issues);
        }

        List<Placement> placed = new ArrayList<>();
        for (Placement pl : placements) {
            if (pl.placed() && tasks.containsKey(pl.taskId())) {
                placed.add(pl);
            }
        }
        checkResourceConflicts(p, tasks, placed, violations);
        checkDailyAndWeeklyLimits(p, tasks, placed, violations);
        checkConsecutive(p, tasks, placed, violations);
        checkCampusTravel(p, tasks, placed, violations);

        violations.sort(Comparator.comparing(Violation::taskId)
                .thenComparing(v -> v.sessionIndex() == null ? 0 : v.sessionIndex())
                .thenComparing(v -> v.code().name()));
        return new Report(violations, issues);
    }

    // ------------------------------------------------------------------ 单任务

    private void checkTask(SchedulingProblem p, TaskDef t, List<Placement> list, List<Violation> violations,
                           List<Issue> issues) {
        int placed = 0;
        Set<Integer> seenSessions = new HashSet<>();
        Map<Integer, Integer> perDay = new HashMap<>();
        Set<Integer> starts = new TreeSet<>();

        for (Placement pl : list) {
            if (!seenSessions.add(pl.sessionIndex())) {
                violations.add(new Violation(ConstraintCode.SESSION_COUNT, t.id(), pl.sessionIndex(), null, null,
                        "教学任务 " + t.label() + " 的课次 " + pl.sessionIndex() + " 重复出现"));
            }
            if (!pl.placed()) {
                continue;
            }
            placed++;
            int end = pl.startPeriod() + t.periodsPerSession() - 1;

            if (!p.teachingDays().contains(pl.day())) {
                violations.add(new Violation(ConstraintCode.TEACHING_TIME, t.id(), pl.sessionIndex(), null, null,
                        "教学任务 " + t.label() + " 被安排在非教学日（" + Violation.dayName(pl.day()) + "）"));
            } else if (!p.validSpan(pl.startPeriod(), t.periodsPerSession())) {
                violations.add(new Violation(ConstraintCode.TEACHING_TIME, t.id(), pl.sessionIndex(), null, null,
                        "教学任务 " + t.label() + " 在 " + Violation.slot(pl.day(), pl.startPeriod(), end)
                                + " 的连续 " + t.periodsPerSession() + " 节不连续或跨越作息段（午休）"));
            }

            TaskRules rules = TaskRules.of(p, t);
            ConstraintCode forbidden = rules.forbiddenBy(pl.day(), pl.startPeriod(), end);
            if (forbidden != null) {
                violations.add(new Violation(forbidden, t.id(), pl.sessionIndex(), null, null,
                        "教学任务 " + t.label() + " 被安排在禁排时段 " + Violation.slot(pl.day(), pl.startPeriod(), end)
                                + "（" + forbidden.label() + "）"));
            }
            if (!rules.fixedOk(pl.day(), pl.startPeriod(), end)) {
                violations.add(new Violation(ConstraintCode.FIXED_TIME, t.id(), pl.sessionIndex(), null, null,
                        "教学任务 " + t.label() + " 要求固定在指定时段，但被安排在 "
                                + Violation.slot(pl.day(), pl.startPeriod(), end)));
            }

            if (t.roomRequired() && pl.roomId() == null) {
                violations.add(new Violation(ConstraintCode.ROOM_MISSING, t.id(), pl.sessionIndex(), null, null,
                        "教学任务 " + t.label() + " 需要教室，但 " + Violation.slot(pl.day(), pl.startPeriod(), end)
                                + " 没有分配教室"));
            } else if (pl.roomId() != null) {
                RoomDef room = p.rooms().get(pl.roomId());
                if (room == null) {
                    violations.add(new Violation(ConstraintCode.ROOM_ASSIGNMENT, t.id(), pl.sessionIndex(), null, null,
                            "教学任务 " + t.label() + " 引用了不存在或已停用的教室 " + pl.roomId()));
                } else {
                    ConstraintCode mismatch = RoomMatcher.mismatch(p, t, room);
                    if (mismatch != null) {
                        violations.add(new Violation(mismatch, t.id(), pl.sessionIndex(), null, null,
                                "教学任务 " + t.label() + " 与教室 " + room.code() + " 不匹配（"
                                        + mismatch.label() + "，教室容量 " + room.capacity()
                                        + "，教学班人数 " + p.studentCount(t) + "）"));
                    }
                    if (RoomMatcher.unavailable(p, room.id(), pl.day(), pl.startPeriod(), end,
                            p.effectiveWeeks(t))) {
                        violations.add(new Violation(ConstraintCode.ROOM_UNAVAILABLE, t.id(), pl.sessionIndex(),
                                null, null, "教室 " + room.code() + " 在 "
                                + Violation.slot(pl.day(), pl.startPeriod(), end) + " 维护或未开放"));
                    }
                }
            }

            perDay.merge(pl.day(), 1, Integer::sum);
            starts.add(pl.startPeriod());
        }

        if (placed != t.sessionsPerWeek()) {
            violations.add(new Violation(ConstraintCode.SESSION_COUNT, t.id(), null, null, null,
                    "教学任务 " + t.label() + " 每周需要 " + t.sessionsPerWeek() + " 次课，实际安排 " + placed + " 次"));
        }
        if (!t.allowSameDay()) {
            perDay.forEach((day, count) -> {
                if (count > 1) {
                    violations.add(new Violation(ConstraintCode.SAME_DAY, t.id(), null, null, null,
                            "教学任务 " + t.label() + " 在" + Violation.dayName(day) + "安排了 " + count
                                    + " 次课，但该任务不允许同一天多次"));
                }
            });
        }
        if (t.uniformStart() && starts.size() > 1) {
            violations.add(new Violation(ConstraintCode.UNIFORM_START, t.id(), null, null, null,
                    "教学任务 " + t.label() + " 要求每次课起始节相同，实际使用了 " + starts.size() + " 个不同起始节"));
        }
        if (p.providedHours(t) != t.totalHours()) {
            issues.add(Issue.warning(ConstraintCode.HOURS_CONSISTENCY.name(), t.id(),
                    "教学任务 " + t.label() + " 学时不一致：单次 " + t.periodsPerSession() + " 节 × 每周 "
                            + t.sessionsPerWeek() + " 次 × 有效周 " + com.campus.platform.scheduling.solver.model.Weeks
                            .count(p.effectiveWeeks(t)) + " 周 = " + p.providedHours(t) + " 学时，任务要求 "
                            + t.totalHours() + " 学时"));
        }
    }

    // ------------------------------------------------------------------ 资源冲突

    private void checkResourceConflicts(SchedulingProblem p, Map<Long, TaskDef> tasks, List<Placement> placed,
                                        List<Violation> violations) {
        Map<Integer, List<Placement>> bySlot = new LinkedHashMap<>();
        for (Placement pl : placed) {
            TaskDef t = tasks.get(pl.taskId());
            for (int period = pl.startPeriod(); period < pl.startPeriod() + t.periodsPerSession(); period++) {
                bySlot.computeIfAbsent(Candidate.slotKey(pl.day(), period), k -> new ArrayList<>()).add(pl);
            }
        }
        Set<String> reported = new HashSet<>();
        for (List<Placement> here : bySlot.values()) {
            if (here.size() < 2) {
                continue;
            }
            for (int i = 0; i < here.size(); i++) {
                for (int j = i + 1; j < here.size(); j++) {
                    Placement a = here.get(i);
                    Placement b = here.get(j);
                    if (a.taskId() == b.taskId()) {
                        continue;
                    }
                    TaskDef ta = tasks.get(a.taskId());
                    TaskDef tb = tasks.get(b.taskId());
                    if ((p.effectiveWeeks(ta) & p.effectiveWeeks(tb)) == 0) {
                        continue;
                    }
                    if (a.roomId() != null && Objects.equals(a.roomId(), b.roomId())) {
                        String key = "ROOM|" + a.roomId() + "|" + a.taskId() + "|" + a.sessionIndex() + "|"
                                + b.taskId() + "|" + b.sessionIndex();
                        if (reported.add(key)) {
                            violations.add(new Violation(ConstraintCode.ROOM_CONFLICT, ta.id(), a.sessionIndex(),
                                    tb.id(), b.sessionIndex(), "教室冲突：教室 " + roomCode(p, a.roomId()) + " 在 "
                                    + Violation.slot(a.day(), a.startPeriod(), a.startPeriod() + ta.periodsPerSession() - 1)
                                    + " 同时被 " + ta.label() + " 与 " + tb.label() + " 占用"));
                        }
                    }
                    ConstraintCode code = ResourceGroups.conflict(p, ta, tb);
                    if (code != null) {
                        String key = code + "|" + ta.id() + "|" + a.sessionIndex() + "|" + tb.id() + "|"
                                + b.sessionIndex();
                        if (reported.add(key)) {
                            violations.add(new Violation(code, ta.id(), a.sessionIndex(), tb.id(), b.sessionIndex(),
                                    (code == ConstraintCode.TEACHER_CONFLICT ? "教师冲突：" : "班级冲突：")
                                            + ta.label() + " 与 " + tb.label() + " 在 "
                                            + Violation.slot(a.day(), a.startPeriod(),
                                            a.startPeriod() + ta.periodsPerSession() - 1) + " 时间重叠"));
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 上限

    private void checkDailyAndWeeklyLimits(SchedulingProblem p, Map<Long, TaskDef> tasks, List<Placement> placed,
                                           List<Violation> violations) {
        Map<Long, Integer> teacherWeekly = new LinkedHashMap<>();
        Map<String, Integer> teacherDaily = new LinkedHashMap<>();
        Map<String, Integer> teacherDailySessions = new LinkedHashMap<>();
        Map<String, Integer> classDaily = new LinkedHashMap<>();

        for (Placement pl : placed) {
            TaskDef t = tasks.get(pl.taskId());
            int len = t.periodsPerSession();
            for (Long teacherId : new LinkedHashSet<>(t.teacherIds())) {
                TeacherDef teacher = p.teachers().get(teacherId);
                if (teacher == null) {
                    continue;
                }
                teacherWeekly.merge(teacherId, len, Integer::sum);
                teacherDaily.merge(teacherId + "|" + pl.day(), len, Integer::sum);
                teacherDailySessions.merge(teacherId + "|" + pl.day(), 1, Integer::sum);
            }
            for (TeachingClassDef.Member m : p.members(t)) {
                if (m.fullClass()) {
                    classDaily.merge(m.classId() + "|" + pl.day(), len, Integer::sum);
                }
            }
        }

        for (Map.Entry<Long, Integer> e : teacherWeekly.entrySet()) {
            TeacherDef teacher = p.teachers().get(e.getKey());
            if (teacher != null && teacher.maxWeeklyPeriods() != null && e.getValue() > teacher.maxWeeklyPeriods()) {
                violations.add(new Violation(ConstraintCode.TEACHER_WEEKLY_MAX, 0, null, null, null,
                        "教师 " + teacher.name() + " 每周排课 " + e.getValue() + " 节，超过上限 "
                                + teacher.maxWeeklyPeriods()));
            }
        }
        for (Map.Entry<String, Integer> e : teacherDaily.entrySet()) {
            long teacherId = Long.parseLong(e.getKey().substring(0, e.getKey().indexOf('|')));
            TeacherDef teacher = p.teachers().get(teacherId);
            if (teacher != null && teacher.maxDailyPeriods() != null && e.getValue() > teacher.maxDailyPeriods()) {
                violations.add(new Violation(ConstraintCode.TEACHER_DAILY_MAX, 0, null, null, null,
                        "教师 " + teacher.name() + " 在" + Violation.dayName(dayOf(e.getKey())) + "排课 "
                                + e.getValue() + " 节，超过每日上限 " + teacher.maxDailyPeriods()));
            }
        }
        for (Map.Entry<String, Integer> e : teacherDailySessions.entrySet()) {
            long teacherId = Long.parseLong(e.getKey().substring(0, e.getKey().indexOf('|')));
            TeacherDef teacher = p.teachers().get(teacherId);
            if (teacher != null && teacher.maxDailySessions() != null
                    && e.getValue() > teacher.maxDailySessions()) {
                violations.add(new Violation(ConstraintCode.TEACHER_DAILY_SESSIONS, 0, null, null, null,
                        "教师 " + teacher.name() + " 在" + Violation.dayName(dayOf(e.getKey())) + "有 "
                                + e.getValue() + " 次课，超过每日课次上限 " + teacher.maxDailySessions()));
            }
        }
        for (Map.Entry<String, Integer> e : classDaily.entrySet()) {
            long classId = Long.parseLong(e.getKey().substring(0, e.getKey().indexOf('|')));
            ClassDef cls = p.classes().get(classId);
            if (cls != null && cls.maxDailyPeriods() != null && e.getValue() > cls.maxDailyPeriods()) {
                violations.add(new Violation(ConstraintCode.CLASS_DAILY_MAX, 0, null, null, null,
                        "班级 " + cls.name() + " 在" + Violation.dayName(dayOf(e.getKey())) + "排课 "
                                + e.getValue() + " 节，超过每日上限 " + cls.maxDailyPeriods()));
            }
        }
    }

    private static int dayOf(String key) {
        return Integer.parseInt(key.substring(key.indexOf('|') + 1));
    }

    private void checkConsecutive(SchedulingProblem p, Map<Long, TaskDef> tasks, List<Placement> placed,
                                  List<Violation> violations) {
        Map<String, Set<Integer>> occupied = new LinkedHashMap<>();
        for (Placement pl : placed) {
            TaskDef t = tasks.get(pl.taskId());
            for (Long teacherId : new LinkedHashSet<>(t.teacherIds())) {
                TeacherDef teacher = p.teachers().get(teacherId);
                if (teacher != null && teacher.maxConsecutivePeriods() != null) {
                    Set<Integer> s = occupied.computeIfAbsent("T" + teacherId + "|" + pl.day(),
                            k -> new TreeSet<>());
                    for (int period = pl.startPeriod(); period < pl.startPeriod() + t.periodsPerSession(); period++) {
                        s.add(period);
                    }
                }
            }
            for (TeachingClassDef.Member m : p.members(t)) {
                ClassDef cls = p.classes().get(m.classId());
                if (m.fullClass() && cls != null && cls.maxConsecutivePeriods() != null) {
                    Set<Integer> s = occupied.computeIfAbsent("C" + m.classId() + "|" + pl.day(),
                            k -> new TreeSet<>());
                    for (int period = pl.startPeriod(); period < pl.startPeriod() + t.periodsPerSession(); period++) {
                        s.add(period);
                    }
                }
            }
        }
        for (Map.Entry<String, Set<Integer>> e : occupied.entrySet()) {
            String key = e.getKey();
            boolean teacher = key.charAt(0) == 'T';
            long id = Long.parseLong(key.substring(1, key.indexOf('|')));
            int day = dayOf(key);
            Integer limit = teacher
                    ? (p.teachers().get(id) == null ? null : p.teachers().get(id).maxConsecutivePeriods())
                    : (p.classes().get(id) == null ? null : p.classes().get(id).maxConsecutivePeriods());
            if (limit == null) {
                continue;
            }
            int run = 0;
            int best = 0;
            Integer prev = null;
            String section = null;
            for (Integer period : e.getValue()) {
                PeriodDef pd = p.periods().get(period);
                String sec = pd == null ? "" : pd.section();
                if (prev != null && period == prev + 1 && Objects.equals(sec, section)) {
                    run++;
                } else {
                    run = 1;
                }
                section = sec;
                prev = period;
                best = Math.max(best, run);
            }
            if (best > limit) {
                ConstraintCode code = teacher ? ConstraintCode.TEACHER_MAX_CONSECUTIVE
                        : ConstraintCode.CLASS_MAX_CONSECUTIVE;
                String name = teacher
                        ? "教师 " + (p.teachers().get(id) == null ? id : p.teachers().get(id).name())
                        : "班级 " + (p.classes().get(id) == null ? id : p.classes().get(id).name());
                violations.add(new Violation(code, 0, null, null, null,
                        name + " 在" + Violation.dayName(day) + "连续上课 " + best + " 节，超过上限 " + limit));
            }
        }
    }

    private void checkCampusTravel(SchedulingProblem p, Map<Long, TaskDef> tasks, List<Placement> placed,
                                   List<Violation> violations) {
        if (!p.multiCampus()) {
            return;
        }
        Map<String, List<Placement>> byResourceDay = new LinkedHashMap<>();
        for (Placement pl : placed) {
            if (pl.roomId() == null || p.rooms().get(pl.roomId()) == null) {
                continue;
            }
            TaskDef t = tasks.get(pl.taskId());
            for (Long teacherId : new LinkedHashSet<>(t.teacherIds())) {
                byResourceDay.computeIfAbsent("T" + teacherId + "|" + pl.day(), k -> new ArrayList<>()).add(pl);
            }
            for (TeachingClassDef.Member m : p.members(t)) {
                if (m.fullClass()) {
                    byResourceDay.computeIfAbsent("C" + m.classId() + "|" + pl.day(), k -> new ArrayList<>())
                            .add(pl);
                }
            }
        }
        for (Map.Entry<String, List<Placement>> e : byResourceDay.entrySet()) {
            List<Placement> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingInt(Placement::startPeriod));
            for (int i = 0; i + 1 < list.size(); i++) {
                Placement a = list.get(i);
                Placement b = list.get(i + 1);
                TaskDef ta = tasks.get(a.taskId());
                int endA = a.startPeriod() + ta.periodsPerSession() - 1;
                RoomDef ra = p.rooms().get(a.roomId());
                RoomDef rb = p.rooms().get(b.roomId());
                if (ra == null || rb == null || ra.campusId() == rb.campusId()) {
                    continue;
                }
                int gap = p.gapMinutes(endA, b.startPeriod());
                int need = p.travelMinutes(ra.campusId(), rb.campusId());
                if (gap >= 0 && gap < need) {
                    violations.add(new Violation(ConstraintCode.CAMPUS_TRAVEL, ta.id(), a.sessionIndex(),
                            b.taskId(), b.sessionIndex(), "跨校区通行时间不足：" + ra.code() + " → " + rb.code()
                            + " 需要 " + need + " 分钟，实际间隔 " + gap + " 分钟（"
                            + Violation.dayName(a.day()) + " 第" + endA + "节至第" + b.startPeriod() + "节）"));
                }
            }
        }
    }

    private static String roomCode(SchedulingProblem p, Long roomId) {
        RoomDef r = p.rooms().get(roomId);
        return r == null ? String.valueOf(roomId) : r.code();
    }

    /** 供外部（局部排课）检查单个课时是否与已有课表冲突：返回冲突的课时列表。 */
    public List<Placement> conflictsOf(SchedulingProblem p, Collection<Placement> existing, Placement candidate) {
        List<Placement> out = new ArrayList<>();
        TaskDef target = p.task(candidate.taskId());
        if (target == null || !candidate.placed()) {
            return out;
        }
        int end = candidate.startPeriod() + target.periodsPerSession() - 1;
        for (Placement other : existing) {
            if (other.taskId() == candidate.taskId() || !other.placed()) {
                continue;
            }
            TaskDef ot = p.task(other.taskId());
            if (ot == null || (p.effectiveWeeks(target) & p.effectiveWeeks(ot)) == 0) {
                continue;
            }
            int otherEnd = other.startPeriod() + ot.periodsPerSession() - 1;
            boolean overlap = other.day() == candidate.day()
                    && other.startPeriod() <= end && candidate.startPeriod() <= otherEnd;
            if (!overlap) {
                continue;
            }
            if (!Objects.equals(other.roomId(), candidate.roomId())
                    && ResourceGroups.conflict(p, target, ot) == null) {
                continue;
            }
            out.add(other);
        }
        return out;
    }
}
