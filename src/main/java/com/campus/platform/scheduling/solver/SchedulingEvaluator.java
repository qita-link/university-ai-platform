package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ClassDef;
import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.Placement;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.campus.platform.scheduling.solver.model.TeacherDef;
import com.campus.platform.scheduling.solver.model.TeachingClassDef;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排课质量评分。
 * <p>
 * 与求解器目标函数使用同一套扣分口径（{@link SoftCost}），但独立计算：
 * 每条软约束给出适用量、违反量与满足率，总分可解释、可复核。
 * 硬约束违规不计入分数（由复核器单独报告，存在违规的版本不允许发布）。
 */
@Component
public final class SchedulingEvaluator {

    /**
     * @param applicable   该软约束的适用量（课次数、天数、任务数等，见各指标定义）
     * @param violated     其中未满足的数量
     * @param penalty      扣分 = 违反量 × 权重
     * @param satisfaction 满足率 0..1
     */
    public record SoftScore(ConstraintCode code, String label, int weight, long applicable, long violated,
                            long penalty, double satisfaction) {
    }

    public record Report(int totalSessions, int placedSessions, int hardViolations, long softPenalty,
                         double score, List<SoftScore> details) {
    }

    public Report evaluate(SchedulingProblem p, List<Placement> placements,
                           SchedulingValidator.Report validation) {
        Map<ConstraintCode, long[]> acc = new LinkedHashMap<>();
        for (ConstraintCode c : ConstraintCode.values()) {
            if (!c.hard()) {
                acc.put(c, new long[]{0, 0});
            }
        }

        Map<Long, TaskDef> tasks = new LinkedHashMap<>();
        p.tasks().forEach(t -> tasks.put(t.id(), t));
        List<Placement> placed = placements.stream()
                .filter(pl -> pl.placed() && tasks.containsKey(pl.taskId()))
                .toList();

        // 与具体课次相关的静态扣分
        for (Placement pl : placed) {
            TaskDef t = tasks.get(pl.taskId());
            int end = pl.startPeriod() + t.periodsPerSession() - 1;
            TaskRules rules = TaskRules.of(p, t);
            Map<ConstraintCode, Integer> raw = SoftCost.raw(p, t, rules, pl.day(), pl.startPeriod(), end,
                    pl.roomId(), baselineOf(p, t.id()));
            for (Map.Entry<ConstraintCode, Integer> e : raw.entrySet()) {
                long[] a = acc.get(e.getKey());
                if (a == null) {
                    continue;
                }
                if (e.getKey() == ConstraintCode.MINIMIZE_CHANGES) {
                    a[0] += 1;
                    a[1] += e.getValue() > 0 ? 1 : 0;
                } else if (isPreference(e.getKey())) {
                    a[0] += 1;
                    a[1] += e.getValue() > 0 ? 1 : 0;
                } else {
                    a[0] += e.getValue();
                    a[1] += e.getValue();
                }
            }
            // 偏好类约束即使没有扣分也要计入“适用”
            for (ConstraintCode code : List.of(ConstraintCode.TEACHER_PREFERENCE,
                    ConstraintCode.CLASS_PREFERENCE, ConstraintCode.COURSE_PREFERENCE)) {
                if (rules.hasSoft(code) && !raw.containsKey(code)) {
                    acc.get(code)[0] += 1;
                }
            }
            if (rules.hasSoft(ConstraintCode.GLOBAL_AVOID) && !raw.containsKey(ConstraintCode.GLOBAL_AVOID)) {
                acc.get(ConstraintCode.GLOBAL_AVOID)[0] += 1;
            }
            if (pl.roomId() != null && !raw.containsKey(ConstraintCode.ROOM_FIT)) {
                acc.get(ConstraintCode.ROOM_FIT)[0] += 1;
            }
            if (p.hasCalendarLoss() && !raw.containsKey(ConstraintCode.HOLIDAY_LOSS)) {
                acc.get(ConstraintCode.HOLIDAY_LOSS)[0] += 1;
            }
            if (t.priority() > SoftCost.PRIORITY_BASELINE
                    && !raw.containsKey(ConstraintCode.PRIORITY_PRIME_TIME)) {
                acc.get(ConstraintCode.PRIORITY_PRIME_TIME)[0] += 1;
            }
        }

        accumulateSpread(p, tasks, placed, acc);
        accumulateRoomStability(p, tasks, placed, acc);
        accumulateClassBalance(p, tasks, placed, acc);
        accumulateTeacherCompact(p, tasks, placed, acc);
        accumulateTeacherHalfDay(p, tasks, placed, acc);

        List<SoftScore> details = new ArrayList<>();
        long weightedSat = 0;
        long weightSum = 0;
        long totalPenalty = 0;
        for (Map.Entry<ConstraintCode, long[]> e : acc.entrySet()) {
            ConstraintCode code = e.getKey();
            int weight = p.weight(code);
            long applicable = e.getValue()[0];
            long violated = Math.min(e.getValue()[1], applicable);
            long penalty = violated * weight;
            totalPenalty += penalty;
            if (applicable <= 0) {
                continue;
            }
            double satisfaction = 1.0 - (double) violated / applicable;
            details.add(new SoftScore(code, code.label(), weight, applicable, violated, penalty, satisfaction));
            if (weight > 0) {
                weightedSat += (long) Math.round(weight * satisfaction * 1000);
                weightSum += weight * 1000L;
            }
        }
        details.sort(Comparator.comparing((SoftScore s) -> s.code().hard())
                .thenComparing(s -> s.code().name()));

        int totalSessions = p.tasks().stream().mapToInt(TaskDef::sessionsPerWeek).sum();
        double placedRatio = totalSessions == 0 ? 1.0 : (double) placed.size() / totalSessions;
        double weightedSatisfaction = weightSum == 0 ? 1.0 : (double) weightedSat / weightSum;
        double score = Math.round(100 * placedRatio * weightedSatisfaction * 100) / 100.0;
        return new Report(totalSessions, placed.size(), validation.violations().size(), totalPenalty, score,
                details);
    }

    private static boolean isPreference(ConstraintCode code) {
        return code == ConstraintCode.TEACHER_PREFERENCE || code == ConstraintCode.CLASS_PREFERENCE
                || code == ConstraintCode.COURSE_PREFERENCE || code == ConstraintCode.GLOBAL_AVOID;
    }

    private static List<Placement> baselineOf(SchedulingProblem p, long taskId) {
        List<Placement> out = new ArrayList<>();
        for (Placement b : p.baseline()) {
            if (b.taskId() == taskId) {
                out.add(b);
            }
        }
        return out;
    }

    /** 相邻两天各上一次课的对数。 */
    private void accumulateSpread(SchedulingProblem p, Map<Long, TaskDef> tasks, List<Placement> placed,
                                  Map<ConstraintCode, long[]> acc) {
        long[] a = acc.get(ConstraintCode.SESSION_SPREAD);
        Map<Long, Set<Integer>> daysByTask = new LinkedHashMap<>();
        for (Placement pl : placed) {
            daysByTask.computeIfAbsent(pl.taskId(), k -> new LinkedHashSet<>()).add(pl.day());
        }
        for (Map.Entry<Long, Set<Integer>> e : daysByTask.entrySet()) {
            List<Integer> days = new ArrayList<>(e.getValue());
            days.sort(Integer::compareTo);
            for (int i = 0; i + 1 < days.size(); i++) {
                a[0]++;
                if (days.get(i + 1) == days.get(i) + 1) {
                    a[1]++;
                }
            }
        }
    }

    /** 同一任务使用的教室数 − 1。 */
    private void accumulateRoomStability(SchedulingProblem p, Map<Long, TaskDef> tasks, List<Placement> placed,
                                         Map<ConstraintCode, long[]> acc) {
        long[] a = acc.get(ConstraintCode.ROOM_STABILITY);
        Map<Long, Set<Long>> roomsByTask = new LinkedHashMap<>();
        Map<Long, Integer> sessionsByTask = new LinkedHashMap<>();
        for (Placement pl : placed) {
            sessionsByTask.merge(pl.taskId(), 1, Integer::sum);
            if (pl.roomId() != null) {
                roomsByTask.computeIfAbsent(pl.taskId(), k -> new LinkedHashSet<>()).add(pl.roomId());
            }
        }
        for (Map.Entry<Long, Integer> e : sessionsByTask.entrySet()) {
            if (e.getValue() < 2) {
                continue;
            }
            int distinct = roomsByTask.getOrDefault(e.getKey(), Set.of()).size();
            a[0] += 1;
            if (distinct > 1) {
                a[1] += distinct - 1L;
            }
        }
    }

    /** 行政班每天超出平均课量的节数。 */
    private void accumulateClassBalance(SchedulingProblem p, Map<Long, TaskDef> tasks, List<Placement> placed,
                                        Map<ConstraintCode, long[]> acc) {
        long[] a = acc.get(ConstraintCode.CLASS_DAILY_BALANCE);
        Map<Long, List<Placement>> byClass = new LinkedHashMap<>();
        for (Placement pl : placed) {
            TaskDef t = tasks.get(pl.taskId());
            for (TeachingClassDef.Member m : p.members(t)) {
                if (m.fullClass()) {
                    byClass.computeIfAbsent(m.classId(), k -> new ArrayList<>()).add(pl);
                }
            }
        }
        int dayCount = Math.max(1, p.teachingDays().size());
        for (Map.Entry<Long, List<Placement>> e : byClass.entrySet()) {
            Map<Integer, Integer> perDay = new LinkedHashMap<>();
            for (Placement pl : e.getValue()) {
                perDay.merge(pl.day(), 1, Integer::sum);
            }
            int total = perDay.values().stream().mapToInt(Integer::intValue).sum();
            int avg = (int) Math.ceil((double) total / dayCount);
            for (Integer day : p.teachingDays()) {
                a[0] += 1;
                int load = perDay.getOrDefault(day, 0);
                if (load > avg) {
                    a[1] += load - avg;
                }
            }
        }
    }

    /** 教师到校天数。 */
    private void accumulateTeacherCompact(SchedulingProblem p, Map<Long, TaskDef> tasks, List<Placement> placed,
                                          Map<ConstraintCode, long[]> acc) {
        long[] a = acc.get(ConstraintCode.TEACHER_COMPACT);
        Map<Long, Set<Integer>> daysByTeacher = new LinkedHashMap<>();
        for (Placement pl : placed) {
            TaskDef t = tasks.get(pl.taskId());
            for (Long teacherId : new LinkedHashSet<>(t.teacherIds())) {
                daysByTeacher.computeIfAbsent(teacherId, k -> new HashSet<>()).add(pl.day());
            }
        }
        for (Map.Entry<Long, Set<Integer>> e : daysByTeacher.entrySet()) {
            TeacherDef teacher = p.teachers().get(e.getKey());
            if (teacher == null || e.getValue().isEmpty()) {
                continue;
            }
            a[0] += e.getValue().size();
            a[1] += Math.max(0, e.getValue().size() - 1);
        }
    }

    /**
     * 教师半天集中：统计每位教师实际占用的「星期 + 半天」格子数，
     * 适用量 = 占用的半天数，违反量 = 比 1 多出来的部分（理想是全部挤进同一个半天）。
     */
    private void accumulateTeacherHalfDay(SchedulingProblem p, Map<Long, TaskDef> tasks, List<Placement> placed,
                                          Map<ConstraintCode, long[]> acc) {
        long[] a = acc.get(ConstraintCode.TEACHER_HALF_DAY);
        Map<Long, Set<String>> halvesByTeacher = new LinkedHashMap<>();
        for (Placement pl : placed) {
            TaskDef t = tasks.get(pl.taskId());
            String section = p.section(pl.startPeriod());
            String key = pl.day() + "|" + (section == null || section.isBlank() ? "UNKNOWN" : section);
            for (Long teacherId : new LinkedHashSet<>(t.teacherIds())) {
                halvesByTeacher.computeIfAbsent(teacherId, k -> new HashSet<>()).add(key);
            }
        }
        for (Map.Entry<Long, Set<String>> e : halvesByTeacher.entrySet()) {
            if (p.teachers().get(e.getKey()) == null || e.getValue().isEmpty()) {
                continue;
            }
            a[0] += e.getValue().size();
            a[1] += Math.max(0, e.getValue().size() - 1);
        }
    }
}
