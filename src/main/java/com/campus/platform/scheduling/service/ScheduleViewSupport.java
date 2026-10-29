package com.campus.platform.scheduling.service;

import com.campus.platform.scheduling.dto.ScheduleDtos;
import com.campus.platform.scheduling.solver.SchedulingValidator;
import com.campus.platform.scheduling.solver.model.ConstraintCode;
import com.campus.platform.scheduling.solver.model.TaskDef;
import com.campus.platform.scheduling.solver.model.TeachingClassDef;
import com.campus.platform.scheduling.solver.Violation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 课表视图装配：把求解/复核对象与数据库实体组合成接口 DTO，集中处理名称与文案。
 */
final class ScheduleViewSupport {

    private ScheduleViewSupport() {
    }

    static List<String> teacherNames(SchedulingProblemLoader.Loaded loaded, long taskId) {
        TaskDef task = loaded.problem().task(taskId);
        if (task == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Long id : task.teacherIds()) {
            List<String> names = loaded.teacherNames().get(id);
            out.add(names == null || names.isEmpty() ? String.valueOf(id) : names.get(0));
        }
        return out;
    }

    static List<String> classNames(SchedulingProblemLoader.Loaded loaded, long taskId) {
        TaskDef task = loaded.problem().task(taskId);
        if (task == null) {
            return List.of();
        }
        TeachingClassDef tc = loaded.problem().teachingClasses().get(task.teachingClassId());
        if (tc == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (TeachingClassDef.Member m : tc.members()) {
            List<String> names = loaded.classNames().get(m.classId());
            out.add(names == null || names.isEmpty() ? String.valueOf(m.classId()) : names.get(0));
        }
        return List.copyOf(out);
    }

    static List<ScheduleDtos.ConflictView> conflicts(SchedulingValidator.Report report,
                                                     SchedulingProblemLoader.Loaded loaded) {
        List<ScheduleDtos.ConflictView> out = new ArrayList<>();
        for (Violation v : report.violations()) {
            String taskCode = loaded.problem().task(v.taskId()) == null ? null
                    : loaded.problem().task(v.taskId()).code();
            String otherCode = v.otherTaskId() == null || loaded.problem().task(v.otherTaskId()) == null ? null
                    : loaded.problem().task(v.otherTaskId()).code();
            Long taskId = v.taskId() == 0 ? null : v.taskId();
            out.add(new ScheduleDtos.ConflictView(v.code().name(), v.code().label(), "ERROR", taskId, taskCode,
                    v.otherTaskId(), null, null, null,
                    taskId == null ? List.of() : teacherNames(loaded, taskId),
                    taskId == null ? List.of() : classNames(loaded, taskId), List.of(),
                    v.message(), suggestion(v.code())));
        }
        return out;
    }

    static String suggestion(ConstraintCode code) {
        return switch (code) {
            case TEACHER_CONFLICT -> "调整其中一门课的时间，或更换授课教师";
            case ROOM_CONFLICT -> "更换教室或调整时段";
            case CLASS_CONFLICT -> "调整其中一门课的时间，避免同一学生群体重叠";
            case TEACHER_UNAVAILABLE -> "避开教师禁排时段，或修改教师可用时间";
            case TEACHER_AVAILABILITY -> "该教师只在特定时间可来校：把课排进其可来时间窗，或调整教师时间要求";
            case ROOM_UNAVAILABLE -> "避开教室维护时段，或改用其它教室";
            case ROOM_CAPACITY -> "换用容量更大的教室，或拆分教学班";
            case ROOM_TYPE, ROOM_FEATURES -> "换用满足类型/设备要求的教室";
            case SESSION_COUNT -> "检查任务每周次数与学时设置，并补齐未安排课次";
            case HOURS_CONSISTENCY -> "调整总学时、每周次数或周次范围使三者一致";
            case TEACHER_WEEKLY_MAX, TEACHER_DAILY_MAX, TEACHER_DAILY_SESSIONS ->
                    "减少该教师的课程或调整其工作量上限";
            case CLASS_DAILY_MAX, CLASS_MAX_CONSECUTIVE -> "把课程分散到其它教学日";
            case CAMPUS_TRAVEL -> "同一半天内不要安排跨校区课程";
            case FIXED_TIME -> "按固定时段放置课程，或取消固定规则";
            case LOCKED -> "解除锁定后重新排课";
            case SAME_DAY -> "把同一任务的多次课分散到不同日期";
            default -> "查看冲突详情后调整课表或基础数据";
        };
    }

    static String timeText(Integer day, Integer start, Integer end) {
        if (day == null || start == null) {
            return "未安排";
        }
        return TaskServiceSupport.dayName(day) + " "
                + (end == null || end.equals(start) ? "第" + start + "节" : "第" + start + "-" + end + "节");
    }
}
