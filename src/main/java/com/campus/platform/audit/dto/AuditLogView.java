package com.campus.platform.audit.dto;

import com.campus.platform.audit.entity.AuditLog;
import com.campus.platform.audit.entity.AuditResult;
import com.campus.platform.audit.entity.AuditSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "审计日志")
public record AuditLogView(
        Long id,
        String traceId,
        Long actorUserId,
        String actorUsername,
        AuditSource source,
        String module,
        String action,
        String targetType,
        String targetId,
        AuditResult result,
        String errorCode,
        @Schema(description = "已脱敏的 JSON 摘要") String detail,
        String clientIp,
        LocalDateTime createdAt) {

    public static AuditLogView from(AuditLog e) {
        return new AuditLogView(e.getId(), e.getTraceId(), e.getActorUserId(), e.getActorUsername(), e.getSource(),
                e.getModule(), e.getAction(), e.getTargetType(), e.getTargetId(), e.getResult(), e.getErrorCode(),
                e.getDetail(), e.getClientIp(), e.getCreatedAt());
    }
}
