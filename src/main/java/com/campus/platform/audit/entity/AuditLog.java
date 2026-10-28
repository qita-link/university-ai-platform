package com.campus.platform.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 审计日志（audit_log），只追加不修改。
 */
@Getter
@Setter
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private Long actorUserId;
    private String actorUsername;
    private AuditSource source;
    private String module;
    private String action;
    private String targetType;
    private String targetId;
    private AuditResult result;
    private String errorCode;
    /** 已脱敏的 JSON 摘要 */
    private String detail;
    private String clientIp;
    private String userAgent;
    private LocalDateTime createdAt;
}
