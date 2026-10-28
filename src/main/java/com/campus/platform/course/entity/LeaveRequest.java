package com.campus.platform.course.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 请假申请（leave_request）。
 * <p>
 * 学生对自己已选课程提交请假；授课教师或管理员审批。状态变更一律使用条件更新
 * （{@code WHERE status = 'PENDING'}），避免并发审批出现两个结果。
 */
@Getter
@Setter
@TableName("leave_request")
public class LeaveRequest extends AuditableEntity {

    private Long studentId;
    private Long courseId;
    private Long scheduleId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String reason;
    private LeaveStatus status;
    private Long reviewerId;
    private String reviewComment;
    private LocalDateTime reviewedAt;

    @Version
    private Integer version;
}
