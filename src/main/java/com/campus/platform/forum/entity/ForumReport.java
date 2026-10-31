package com.campus.platform.forum.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 论坛举报（forum_report）。
 * <p>
 * 同一个人对同一个目标只能举报一次（唯一键 {@code target_type + target_id + reporter_id}），
 * 重复提交按已举报处理，不报错也不新增记录。
 */
@Getter
@Setter
@TableName("forum_report")
public class ForumReport extends AuditableEntity {

    private ReportTargetType targetType;
    private Long targetId;
    private Long reporterId;
    /** SPAM/ABUSE/PORN/ILLEGAL/ACADEMIC/OTHER */
    private String reason;
    private String detail;
    private ReportStatus status;
    private Long handledBy;
    private LocalDateTime handledAt;
    private String handleNote;
}
