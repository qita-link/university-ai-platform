package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.RuleTarget;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.campus.platform.scheduling.solver.model.TeachingClassDef;
import com.campus.platform.scheduling.solver.model.TimeRule;
import com.campus.platform.scheduling.solver.model.TimeRuleKind;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 与某个教学任务相关的全部时间规则（已按周次过滤：规则周次与任务上课周次不相交的规则不生效）。
 * <p>
 * 规则来源与约束代码对应关系：
 * <ul>
 *   <li>TEACHER → TEACHER_UNAVAILABLE / TEACHER_PREFERENCE（共同授课的每位教师），
 *       以及可来时间窗 AVAILABLE → TEACHER_AVAILABILITY</li>
 *   <li>CLASS、TEACHING_CLASS → CLASS_FORBIDDEN / CLASS_PREFERENCE（教学班的每个行政班 + 教学班本身）</li>
 *   <li>COURSE、TASK → COURSE_FORBIDDEN / COURSE_PREFERENCE，FIXED 固定时段</li>
 *   <li>GLOBAL → COURSE_FORBIDDEN / GLOBAL_AVOID</li>
 * </ul>
 * 偏好口径：同一“对象”只要定义了 PREFERRED 规则，课次不落在其任一偏好窗口内就计一次（取该对象偏好规则的最大倍数）；
 * 每条与课次重叠的 AVOID 规则计其倍数。
 * <p>
 * 可来时间口径：同一教师只要定义了任一 AVAILABLE 规则，课次就必须整体落在其中某个窗口内，
 * 否则按 TEACHER_AVAILABILITY 硬约束判冲突（外聘教师「只能周三来」「只能上午来」）。
 */
public final class TaskRules {

    /** 一个规则拥有者（某教师、某班级、课程、全局）的规则集合。 */
    private record Owner(ConstraintCode hardCode, ConstraintCode softCode, List<TimeRule> forbidden,
                         List<TimeRule> avoid, List<TimeRule> preferred, List<TimeRule> available,
                         ConstraintCode availabilityCode) {

        boolean hasAvailability() {
            return availabilityCode != null && !available.isEmpty();
        }
    }

    private final List<Owner> owners = new ArrayList<>();
    private final List<TimeRule> fixed = new ArrayList<>();

    private TaskRules() {
    }

    public static TaskRules of(SchedulingProblem p, TaskDef t) {
        TaskRules tr = new TaskRules();
        long weeks = p.effectiveWeeks(t);
        for (Long teacherId : t.teacherIds()) {
            tr.add(p.rulesOf(RuleTarget.TEACHER, teacherId), weeks,
                    ConstraintCode.TEACHER_UNAVAILABLE, ConstraintCode.TEACHER_PREFERENCE,
                    ConstraintCode.TEACHER_AVAILABILITY);
        }
        for (TeachingClassDef.Member m : p.members(t)) {
            tr.add(p.rulesOf(RuleTarget.CLASS, m.classId()), weeks,
                    ConstraintCode.CLASS_FORBIDDEN, ConstraintCode.CLASS_PREFERENCE, null);
        }
        tr.add(p.rulesOf(RuleTarget.TEACHING_CLASS, t.teachingClassId()), weeks,
                ConstraintCode.CLASS_FORBIDDEN, ConstraintCode.CLASS_PREFERENCE, null);
        List<TimeRule> course = new ArrayList<>(p.rulesOf(RuleTarget.COURSE, t.courseId()));
        course.addAll(p.rulesOf(RuleTarget.TASK, t.id()));
        tr.add(course, weeks, ConstraintCode.COURSE_FORBIDDEN, ConstraintCode.COURSE_PREFERENCE, null);
        tr.add(p.rulesOf(RuleTarget.GLOBAL, 0L), weeks, ConstraintCode.COURSE_FORBIDDEN,
                ConstraintCode.GLOBAL_AVOID, null);
        return tr;
    }

    private void add(List<TimeRule> rules, long weeks, ConstraintCode hard, ConstraintCode soft,
                     ConstraintCode availabilityCode) {
        List<TimeRule> f = new ArrayList<>();
        List<TimeRule> a = new ArrayList<>();
        List<TimeRule> pr = new ArrayList<>();
        List<TimeRule> av = new ArrayList<>();
        for (TimeRule r : rules) {
            if ((r.weeksMask() & weeks) == 0) {
                continue;
            }
            switch (r.kind()) {
                case FORBIDDEN -> f.add(r);
                case AVOID -> a.add(r);
                case PREFERRED -> pr.add(r);
                case AVAILABLE -> {
                    if (availabilityCode != null) {
                        av.add(r);
                    }
                }
                case FIXED -> {
                    if (r.target() == RuleTarget.COURSE || r.target() == RuleTarget.TASK) {
                        fixed.add(r);
                    }
                }
            }
        }
        if (!f.isEmpty() || !a.isEmpty() || !pr.isEmpty() || !av.isEmpty()) {
            owners.add(new Owner(hard, soft, f, a, pr, av, availabilityCode));
        }
    }

    /** 第一个禁止该课次的硬约束代码；没有返回 null。 */
    public ConstraintCode forbiddenBy(int day, int start, int end) {
        for (Owner o : owners) {
            for (TimeRule r : o.forbidden()) {
                if (r.overlaps(day, start, end)) {
                    return o.hardCode();
                }
            }
            if (o.hasAvailability() && !insideAvailability(o.available(), day, start, end)) {
                return o.availabilityCode();
            }
        }
        return null;
    }

    private static boolean insideAvailability(List<TimeRule> available, int day, int start, int end) {
        for (TimeRule r : available) {
            if (r.contains(day, start, end)) {
                return true;
            }
        }
        return false;
    }

    /** 没有 FIXED 规则，或课次完全落在某个固定窗口内。 */
    public boolean fixedOk(int day, int start, int end) {
        if (fixed.isEmpty()) {
            return true;
        }
        for (TimeRule r : fixed) {
            if (r.contains(day, start, end)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasFixed() {
        return !fixed.isEmpty();
    }

    /** 课次在各软约束上的原始扣分（未乘约束权重）。 */
    public Map<ConstraintCode, Integer> softPenalty(int day, int start, int end) {
        Map<ConstraintCode, Integer> m = new EnumMap<>(ConstraintCode.class);
        for (Owner o : owners) {
            int pen = 0;
            for (TimeRule r : o.avoid()) {
                if (r.overlaps(day, start, end)) {
                    pen += r.weight();
                }
            }
            if (!o.preferred().isEmpty()) {
                boolean inside = false;
                int maxW = 1;
                for (TimeRule r : o.preferred()) {
                    maxW = Math.max(maxW, r.weight());
                    if (r.contains(day, start, end)) {
                        inside = true;
                    }
                }
                if (!inside) {
                    pen += maxW;
                }
            }
            if (pen > 0) {
                m.merge(o.softCode(), pen, Integer::sum);
            }
        }
        return m;
    }

    /** 是否存在会影响该软约束的规则（用于评分的“适用项”统计）。 */
    public boolean hasSoft(ConstraintCode code) {
        return owners.stream().anyMatch(o -> o.softCode() == code
                && (!o.avoid().isEmpty() || !o.preferred().isEmpty()));
    }

    static boolean isKind(TimeRule r, TimeRuleKind k) {
        return r.kind() == k;
    }
}
