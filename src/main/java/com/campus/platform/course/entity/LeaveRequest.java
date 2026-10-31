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
    /** 提交时学生所在行政班 */
    private Long classId;
    /** 提交时解析到的导员（为空表示该班未配置导员，流程自动跳过导员环节） */
    private Long counselorId;
    private Long courseId;
    private Long scheduleId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String reason;
    private LeaveStatus status;
    /** 审批顺序快照，如 TEACHER,COUNSELOR */
    private String approvalOrder;
    /** 应审批级数（提交时按设置与导员配置确定） */
    private Integer totalStages;
    /** 当前待审批级数，从 1 开始 */
    private Integer currentStage;
    private Long reviewerId;
    private String reviewComment;
    private LocalDateTime reviewedAt;
    /** 销假时间：通过后学生返校销假，非空表示已销假 */
    private LocalDateTime revokedAt;
    private String revokeNote;
    private Long revokedBy;

    @Version
    private Integer version;
}
