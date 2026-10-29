package com.campus.platform.scheduling.solver;

import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.SchedulingProblem;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.campus.platform.scheduling.solver.model.TeachingClassDef;
import com.campus.platform.scheduling.solver.model.Weeks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时间资源分组：同一组内的任务在周次相交时不能同时上课，且共享每日/连续节数等上限。
 * <ul>
 *   <li>教师：该教师参与的全部任务（含共同授课）。</li>
 *   <li>行政班：整班参加的任务 F；若有选修抽取任务 E（只含该班部分学生），则每个 e∈E 形成一组 F∪{e}：
 *       不同选修任务之间允许同时段（学生只会选其一），但都不能与整班课冲突。</li>
 *   <li>教学班：含部分成员或没有成员的教学班单独成组（整班成员已被行政班组覆盖）。</li>
 * </ul>
 * 教室冲突与候选所选教室相关，不在此分组。
 */
public final class ResourceGroups {

    public enum Kind { TEACHER, CLASS, TEACHING_CLASS }

    /**
     * @param tasks       组内任务（问题中的任务序号）
     * @param weekMaximal 按周次去重后的极大并发集合：只有同一集合内的任务才可能在同一周上课
     */
    public record Group(Kind kind, long resourceId, List<Integer> tasks, List<List<Integer>> weekMaximal) {
        public ConstraintCode conflictCode() {
            return kind == Kind.TEACHER ? ConstraintCode.TEACHER_CONFLICT : ConstraintCode.CLASS_CONFLICT;
        }
    }

    private ResourceGroups() {
    }

    public static List<Group> build(SchedulingProblem p, List<TaskDef> tasks) {
        List<Group> out = new ArrayList<>();
        Map<Long, List<Integer>> byTeacher = new LinkedHashMap<>();
        Map<Long, List<Integer>> fullByClass = new LinkedHashMap<>();
        Map<Long, List<Integer>> partialByClass = new LinkedHashMap<>();
        Map<Long, List<Integer>> byTeachingClass = new LinkedHashMap<>();
        Set<Long> ownGroupTeachingClasses = new LinkedHashSet<>();
        for (int i = 0; i < tasks.size(); i++) {
            TaskDef t = tasks.get(i);
            for (Long teacher : new LinkedHashSet<>(t.teacherIds())) {
                byTeacher.computeIfAbsent(teacher, k -> new ArrayList<>()).add(i);
            }
            List<TeachingClassDef.Member> members = p.members(t);
            for (TeachingClassDef.Member m : members) {
                (m.fullClass() ? fullByClass : partialByClass).computeIfAbsent(m.classId(), k -> new ArrayList<>()).add(i);
                if (!m.fullClass()) {
                    ownGroupTeachingClasses.add(t.teachingClassId());
                }
            }
            if (members.isEmpty()) {
                ownGroupTeachingClasses.add(t.teachingClassId());
            }
            byTeachingClass.computeIfAbsent(t.teachingClassId(), k -> new ArrayList<>()).add(i);
        }
        byTeacher.forEach((id, list) -> out.add(group(p, tasks, Kind.TEACHER, id, list)));
        Set<Long> classIds = new LinkedHashSet<>(fullByClass.keySet());
        classIds.addAll(partialByClass.keySet());
        for (Long cls : classIds) {
            List<Integer> full = fullByClass.getOrDefault(cls, List.of());
            List<Integer> partial = partialByClass.getOrDefault(cls, List.of());
            if (partial.isEmpty()) {
                out.add(group(p, tasks, Kind.CLASS, cls, full));
            } else {
                // 同一教学班的多个选修任务属于同一批学生，放在一起
                Map<Long, List<Integer>> byTc = new LinkedHashMap<>();
                for (Integer e : partial) {
                    byTc.computeIfAbsent(tasks.get(e).teachingClassId(), k -> new ArrayList<>()).add(e);
                }
                for (List<Integer> es : byTc.values()) {
                    List<Integer> g = new ArrayList<>(full);
                    g.addAll(es);
                    out.add(group(p, tasks, Kind.CLASS, cls, g));
                }
            }
        }
        for (Long tc : ownGroupTeachingClasses) {
            out.add(group(p, tasks, Kind.TEACHING_CLASS, tc, byTeachingClass.get(tc)));
        }
        return out;
    }

    private static Group group(SchedulingProblem p, List<TaskDef> tasks, Kind kind, long id, List<Integer> list) {
        return new Group(kind, id, List.copyOf(list), weekMaximal(p, tasks, list));
    }

    /** 逐周求“当周上课的任务集合”，去重并去掉被包含的子集。 */
    static List<List<Integer>> weekMaximal(SchedulingProblem p, List<TaskDef> tasks, List<Integer> list) {
        Map<Set<Integer>, Boolean> sets = new LinkedHashMap<>();
        for (int w = 1; w <= Weeks.MAX_WEEKS; w++) {
            Set<Integer> s = new LinkedHashSet<>();
            for (Integer i : list) {
                if (Weeks.has(p.effectiveWeeks(tasks.get(i)), w)) {
                    s.add(i);
                }
            }
            if (!s.isEmpty()) {
                sets.put(s, Boolean.TRUE);
            }
        }
        List<Set<Integer>> all = new ArrayList<>(sets.keySet());
        List<List<Integer>> out = new ArrayList<>();
        for (Set<Integer> s : all) {
            boolean dominated = false;
            for (Set<Integer> o : all) {
                if (o != s && o.size() > s.size() && o.containsAll(s)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                out.add(new ArrayList<>(s));
            }
        }
        return out;
    }

    /** 两个任务之间的时间冲突类型（不含教室）；不冲突返回 null。只看资源，不看周次。 */
    public static ConstraintCode conflict(SchedulingProblem p, TaskDef a, TaskDef b) {
        for (Long ta : a.teacherIds()) {
            if (b.teacherIds().contains(ta)) {
                return ConstraintCode.TEACHER_CONFLICT;
            }
        }
        if (a.teachingClassId() == b.teachingClassId()) {
            return ConstraintCode.CLASS_CONFLICT;
        }
        Map<Long, Boolean> am = new HashMap<>();
        for (TeachingClassDef.Member m : p.members(a)) {
            am.put(m.classId(), m.fullClass());
        }
        for (TeachingClassDef.Member m : p.members(b)) {
            Boolean fa = am.get(m.classId());
            if (fa != null && (fa || m.fullClass())) {
                return ConstraintCode.CLASS_CONFLICT;
            }
        }
        return null;
    }
}
