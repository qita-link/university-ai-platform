package com.campus.platform.ai.controller;

import com.campus.platform.ai.dto.AiToolCallQuery;
import com.campus.platform.ai.dto.AiToolCallRequest;
import com.campus.platform.ai.dto.AiToolCallView;
import com.campus.platform.ai.dto.AiToolRejectRequest;
import com.campus.platform.ai.dto.AiToolView;
import com.campus.platform.ai.service.AiToolService;
import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 工具调用入口。
 * <p>
 * 只读工具调用即执行；写操作工具只生成待确认记录，必须调用 {@code /confirm} 才会执行。
 * 工具最终调用的是各业务模块的 Service，权限与数据归属仍由那些 Service 判断。
 */
@Tag(name = "AI 工具调用", description = "工具目录、调用、确认/拒绝与调用记录")
@RestController
@RequestMapping("/api/v1/ai")
public class AiToolController {

    private final AiToolService toolService;

    public AiToolController(AiToolService toolService) {
        this.toolService = toolService;
    }

    @Operation(summary = "可用工具目录", description = "需要 ai:tool:use；只返回当前用户有权限使用的工具")
    @PreAuthorize("hasAuthority('ai:tool:use')")
    @GetMapping("/tools")
    public ApiResponse<List<AiToolView>> tools(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(toolService.listTools(user));
    }

    @Operation(summary = "调用工具",
            description = "只读工具立即执行并返回结果；写操作工具生成待确认记录，需再调用 confirm")
    @PreAuthorize("hasAuthority('ai:tool:use')")
    @PostMapping("/tool-calls")
    public ApiResponse<AiToolCallView> invoke(@AuthenticationPrincipal AuthenticatedUser user,
                                              @Valid @RequestBody AiToolCallRequest request) {
        return ApiResponse.ok(toolService.invoke(user, request));
    }

    @Operation(summary = "确认执行", description = "仅本人待确认记录；过期返回 409，并发确认只有一个成功")
    @PreAuthorize("hasAuthority('ai:tool:use')")
    @PostMapping("/tool-calls/{id}/confirm")
    public ApiResponse<AiToolCallView> confirm(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable long id) {
        return ApiResponse.ok(toolService.confirm(user, id));
    }

    @Operation(summary = "拒绝执行", description = "仅本人待确认记录；拒绝后不会触发任何业务动作")
    @PreAuthorize("hasAuthority('ai:tool:use')")
    @PostMapping("/tool-calls/{id}/reject")
    public ApiResponse<AiToolCallView> reject(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable long id,
                                              @Valid @RequestBody(required = false) AiToolRejectRequest request) {
        return ApiResponse.ok(toolService.reject(user, id, request == null ? null : request.reason()));
    }

    @Operation(summary = "工具调用记录", description = "本人记录；管理员可查看全部，并可按 userId 过滤")
    @PreAuthorize("hasAuthority('ai:tool:use')")
    @GetMapping("/tool-calls")
    public ApiResponse<PageResult<AiToolCallView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @Valid @ParameterObject AiToolCallQuery query) {
        return ApiResponse.ok(toolService.list(user, query));
    }

    @Operation(summary = "工具调用详情", description = "本人记录；管理员可查看全部")
    @PreAuthorize("hasAuthority('ai:tool:use')")
    @GetMapping("/tool-calls/{id}")
    public ApiResponse<AiToolCallView> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(toolService.get(user, id));
    }
}
