package com.campus.platform.scheduling.service;

import com.campus.platform.scheduling.dto.TaskDtos;
import com.campus.platform.scheduling.solver.model.Issue;

/**
 * 排课服务层共享的小工具：问题对象到接口 DTO 的映射、周次与时间文案。
 */
final class TaskServiceSupport {

    private TaskServiceSupport() {
    }

    static TaskDtos.IssueView issueView(Issue issue) {
        return new TaskDtos.IssueView(issue.severity().name(), issue.code(), issue.taskId(), issue.message());
    }

    static String slotText(Integer day, Integer start, Integer end) {
        if (day == null || start == null) {
            return "未安排";
        }
        String periods = end == null || end.equals(start) ? "第" + start + "节" : "第" + start + "-" + end + "节";
        return dayName(day) + " " + periods;
    }

    static String dayName(int day) {
        String[] names = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return day >= 1 && day <= 7 ? names[day] : "第" + day + "天";
    }
}
