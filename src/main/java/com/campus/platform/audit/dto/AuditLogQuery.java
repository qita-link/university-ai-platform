package com.campus.platform.audit.dto;

import com.campus.platform.audit.entity.AuditResult;
import com.campus.platform.audit.entity.AuditSource;
import com.campus.platform.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Getter
@Setter
@Schema(description = "审计日志查询条件")
public class AuditLogQuery extends PageQuery {

    @Schema(description = "操作人 ID")
    private Long actorUserId;

    @Schema(description = "模块，如 knowledge")
    @Size(max = 32)
    private String module;

    @Schema(description = "动作，如 KB_DOCUMENT_DELETE")
    @Size(max = 64)
    private String action;

    @Schema(description = "来源")
    private AuditSource source;

    @Schema(description = "结果")
    private AuditResult result;

    @Schema(description = "目标类型")
    @Size(max = 32)
    private String targetType;

    @Schema(description = "目标 ID")
    @Size(max = 64)
    private String targetId;

    @Schema(description = "起始时间（含），ISO 格式，如 2026-10-01T00:00:00")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime from;

    @Schema(description = "结束时间（不含）")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime to;
}
