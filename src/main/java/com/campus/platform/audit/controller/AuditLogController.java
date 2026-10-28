package com.campus.platform.audit.controller;

import com.campus.platform.audit.dto.AuditLogQuery;
import com.campus.platform.audit.dto.AuditLogView;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "审计日志", description = "系统操作日志与 AI 工具调用审计（仅管理员）")
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditService auditService;

    public AuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    @Operation(summary = "分页查询审计日志", description = "需要 audit:read 权限")
    @PreAuthorize("hasAuthority('audit:read')")
    @GetMapping
    public ApiResponse<PageResult<AuditLogView>> list(@Valid @ParameterObject AuditLogQuery query) {
        return ApiResponse.ok(auditService.query(query));
    }
}
