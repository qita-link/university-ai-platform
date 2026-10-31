package com.campus.platform.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 请假审批流水（leave_approval）：每级一条，唯一键 (leave_id, stage) 保证并发下只有一条结论。
 */
@Getter
@Setter
@TableName("leave_approval")
public class LeaveApproval {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leaveId;
    private Integer stage;
    private LeaveApproverRole approverRole;
    private Long approverId;
    private LeaveDecision decision;
    private String comment;
    private LocalDateTime decidedAt;
}
