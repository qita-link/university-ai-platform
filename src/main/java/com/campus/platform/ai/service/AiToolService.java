package com.campus.platform.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.ai.config.AiToolProperties;
import com.campus.platform.ai.dto.AiToolCallQuery;
import com.campus.platform.ai.dto.AiToolCallRequest;
import com.campus.platform.ai.dto.AiToolCallView;
import com.campus.platform.ai.dto.AiToolView;
import com.campus.platform.ai.entity.AiToolCall;
import com.campus.platform.ai.entity.AiToolCallStatus;
import com.campus.platform.ai.mapper.AiToolCallMapper;
import com.campus.platform.ai.tool.AiTool;
import com.campus.platform.ai.tool.AiToolArguments;
import com.campus.platform.ai.tool.AiToolRegistry;
import com.campus.platform.ai.tool.ToolPreview;
import com.campus.platform.ai.tool.ToolResult;
import com.campus.platform.audit.entity.AuditSource;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 工具调用。
 * <p>
 * 三种执行路径：
 * <ul>
 *   <li><b>只读工具</b>：调用即执行，结果回给调用方，同时落一条 SUCCEEDED/FAILED 记录；</li>
 *   <li><b>写操作工具</b>：不执行，只生成 PENDING_CONFIRM 记录，附“目标与影响”预览与过期时间；</li>
 *   <li><b>确认执行</b>：条件更新认领（PENDING_CONFIRM → EXECUTING）后调用同一份参数执行，
 *       并发确认只有一个请求能认领成功，其余返回 409。</li>
 * </ul>
 * 权限是两层：先校验 {@code ai:tool:use} 与工具声明的功能权限，再交给被调用的业务 Service 做数据归属判断，
 * 因此工具无法绕过任何既有权限规则。所有调用（含失败、拒绝）都写 {@code audit_log}，source 为 {@code AI_TOOL}。
 */
@Slf4j
@Service
public class AiToolService {

    static final String MODULE = "ai";
    private static final int MAX_SUMMARY = 2000;
    private static final int MAX_PREVIEW = 1000;
    private static final int MAX_ERROR = 500;

    private final AiToolRegistry registry;
    private final AiToolCallMapper callMapper;
    private final AuditService auditService;
    private final AiToolProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AiToolService(AiToolRegistry registry, AiToolCallMapper callMapper, AuditService auditService,
                         AiToolProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.registry = registry;
        this.callMapper = callMapper;
        this.auditService = auditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 目录

    /** 当前用户可用的工具目录（按功能权限过滤），可直接作为模型提示词中的能力清单。 */
    public List<AiToolView> listTools(AuthenticatedUser user) {
        Permissions.require(user, Permissions.AI_TOOL_USE);
        return registry.all().stream()
                .filter(tool -> canUse(user, tool))
                .map(AiToolView::of)
                .toList();
    }

    // ------------------------------------------------------------------ 调用

    public AiToolCallView invoke(AuthenticatedUser user, AiToolCallRequest request) {
        Permissions.require(user, Permissions.AI_TOOL_USE);
        AiTool tool = registry.require(request.tool());
        requireUsable(user, tool);
        AiToolArguments args = AiToolArguments.of(request.arguments());
        LocalDateTime now = LocalDateTime.now(clock);

        if (tool.sideEffect()) {
            ToolPreview preview = tool.preview(user, args);
            AiToolCall call = newCall(user, request, tool, args, now);
            call.setStatus(AiToolCallStatus.PENDING_CONFIRM);
            call.setPreview(truncate(preview == null ? null : preview.summary(), MAX_PREVIEW));
            call.setConfirmExpiresAt(now.plus(properties.confirmTtl()));
            callMapper.insert(call);
            audit(call, "AI_TOOL_REQUEST", true, null);
            return view(call, null);
        }

        long start = System.currentTimeMillis();
        ToolResult result = null;
        BusinessException failure = null;
        RuntimeException unexpected = null;
        try {
            result = tool.execute(user, args);
        } catch (BusinessException ex) {
            failure = ex;
        } catch (RuntimeException ex) {
            unexpected = ex;
        }
        long latency = System.currentTimeMillis() - start;

        AiToolCall call = newCall(user, request, tool, args, now);
        call.setLatencyMs(toInt(latency));
        call.setExecutedAt(LocalDateTime.now(clock));
        if (failure == null && unexpected == null) {
            call.setStatus(AiToolCallStatus.SUCCEEDED);
            call.setResultSummary(truncate(result == null ? null : result.summary(), MAX_SUMMARY));
        } else {
            String code = failure != null ? failure.getErrorCode().name() : ErrorCode.INTERNAL_ERROR.name();
            String message = failure != null ? failure.getMessage() : "工具执行失败";
            call.setStatus(AiToolCallStatus.FAILED);
            call.setErrorCode(code);
            call.setErrorMessage(truncate(message, MAX_ERROR));
        }
        callMapper.insert(call);
        audit(call, "AI_TOOL_INVOKE", false, call.getErrorCode());

        if (failure != null) {
            throw failure;
        }
        if (unexpected != null) {
            log.warn("AI 工具执行异常: tool={}", tool.name(), unexpected);
            throw unexpected;
        }
        return view(call, result == null ? null : result.data());
    }

    /**
     * 确认并执行写操作。
     * <p>
     * 认领（PENDING_CONFIRM → EXECUTING）与业务执行分离：认领成功后即使业务失败，也会把记录标记为 FAILED，
     * 不会停留在 EXECUTING；并发确认时只有一个请求能认领，另一个返回 409。
     */
    public AiToolCallView confirm(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.AI_TOOL_USE);
        AiToolCall call = requireOwnCall(user, id);
        if (!call.getStatus().isPendingConfirm()) {
            throw new BusinessException(ErrorCode.AI_TOOL_STATUS_INVALID,
                    "该调用当前状态为 " + call.getStatus() + "，不能确认执行");
        }
        AiTool tool = registry.require(call.getToolName());
        requireUsable(user, tool);

        LocalDateTime now = LocalDateTime.now(clock);
        if (callMapper.claim(id, user.userId(), now) != 1) {
            AiToolCall fresh = callMapper.selectById(id);
            if (fresh != null && fresh.getStatus().isPendingConfirm()) {
                // 认领失败且仍是待确认 → 已超过确认截止时间
                callMapper.markExpired(id, user.userId(), now);
                AiToolCall expired = callMapper.selectById(id);
                if (expired != null) {
                    audit(expired, "AI_TOOL_EXPIRE", false, ErrorCode.AI_TOOL_CONFIRM_EXPIRED.name());
                }
                throw new BusinessException(ErrorCode.AI_TOOL_CONFIRM_EXPIRED);
            }
            throw new BusinessException(ErrorCode.AI_TOOL_STATUS_INVALID, "该调用已被处理，请刷新后重试");
        }

        Map<String, Object> arguments = parseArguments(call);
        long start = System.currentTimeMillis();
        ToolResult result = null;
        BusinessException failure = null;
        RuntimeException unexpected = null;
        try {
            result = tool.execute(user, AiToolArguments.of(arguments));
        } catch (BusinessException ex) {
            failure = ex;
        } catch (RuntimeException ex) {
            unexpected = ex;
        }
        long latency = System.currentTimeMillis() - start;

        if (failure == null && unexpected == null) {
            callMapper.finish(id, AiToolCallStatus.SUCCEEDED,
                    truncate(result == null ? null : result.summary(), MAX_SUMMARY),
                    null, null, toInt(latency), LocalDateTime.now(clock));
        } else {
            String code = failure != null ? failure.getErrorCode().name() : ErrorCode.INTERNAL_ERROR.name();
            String message = failure != null ? failure.getMessage() : "工具执行失败";
            callMapper.finish(id, AiToolCallStatus.FAILED, null, code,
                    truncate(message, MAX_ERROR), toInt(latency), LocalDateTime.now(clock));
        }
        AiToolCall updated = callMapper.selectById(id);
        audit(updated, "AI_TOOL_CONFIRM", true, updated == null ? null : updated.getErrorCode());

        if (failure != null) {
            throw failure;
        }
        if (unexpected != null) {
            log.warn("AI 工具确认执行异常: tool={}", tool.name(), unexpected);
            throw unexpected;
        }
        return view(updated, result == null ? null : result.data());
    }

    /** 拒绝执行：待确认记录置为 REJECTED，不触发任何业务动作。 */
    public AiToolCallView reject(AuthenticatedUser user, long id, String reason) {
        Permissions.require(user, Permissions.AI_TOOL_USE);
        AiToolCall call = requireOwnCall(user, id);
        if (!call.getStatus().isPendingConfirm()) {
            throw new BusinessException(ErrorCode.AI_TOOL_STATUS_INVALID,
                    "该调用当前状态为 " + call.getStatus() + "，不能拒绝");
        }
        String text = reason == null || reason.isBlank() ? "用户拒绝执行" : reason.trim();
        if (callMapper.reject(id, user.userId(), truncate(text, MAX_ERROR), LocalDateTime.now(clock)) != 1) {
            throw new BusinessException(ErrorCode.AI_TOOL_STATUS_INVALID, "该调用已被处理，请刷新后重试");
        }
        AiToolCall updated = callMapper.selectById(id);
        audit(updated, "AI_TOOL_REJECT", true, null);
        return view(updated, null);
    }

    // ------------------------------------------------------------------ 查询

    /** 本人记录；管理员可查看全部（可按 userId 过滤）。 */
    public PageResult<AiToolCallView> list(AuthenticatedUser user, AiToolCallQuery query) {
        Permissions.require(user, Permissions.AI_TOOL_USE);
        // 先结算过期：确认期已过却仍挂 PENDING_CONFIRM 的记录会让界面出现“点不动的待确认”
        callMapper.expireOverdue(user.userId(), LocalDateTime.now(clock));
        LambdaQueryWrapper<AiToolCall> w = new LambdaQueryWrapper<>();
        if (user.isAdmin()) {
            w.eq(query.getUserId() != null, AiToolCall::getUserId, query.getUserId());
        } else {
            w.eq(AiToolCall::getUserId, user.userId());
        }
        w.eq(query.getStatus() != null, AiToolCall::getStatus, query.getStatus())
                .eq(query.getToolName() != null && !query.getToolName().isBlank(),
                        AiToolCall::getToolName, query.getToolName())
                .orderByDesc(AiToolCall::getId);
        Page<AiToolCall> pageRequest = query.toPage();
        IPage<AiToolCall> page = callMapper.selectPage(pageRequest, w);
        return new PageResult<>(page.getRecords().stream().map(c -> view(c, null)).toList(),
                page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public AiToolCallView get(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.AI_TOOL_USE);
        AiToolCall call = callMapper.selectById(id);
        if (call == null || (!user.isAdmin() && !Objects.equals(call.getUserId(), user.userId()))) {
            throw BusinessException.notFound("工具调用记录");
        }
        // 单条也照样结算过期，避免详情页显示“待确认”而操作必然失败
        if (call.getStatus() != null && call.getStatus().isPendingConfirm()
                && call.getConfirmExpiresAt() != null
                && call.getConfirmExpiresAt().isBefore(LocalDateTime.now(clock))) {
            callMapper.markExpired(id, call.getUserId(), LocalDateTime.now(clock));
            call = callMapper.selectById(id);
        }
        return view(call, null);
    }

    // ------------------------------------------------------------------ 内部

    private AiToolCall requireOwnCall(AuthenticatedUser user, long id) {
        AiToolCall call = callMapper.selectById(id);
        if (call == null || !Objects.equals(call.getUserId(), user.userId())) {
            throw BusinessException.notFound("工具调用记录");
        }
        return call;
    }

    private static boolean canUse(AuthenticatedUser user, AiTool tool) {
        if (user == null) {
            return false;
        }
        List<String> required = tool.requiredPermissions();
        return required.isEmpty() || required.stream().anyMatch(user::hasPermission);
    }

    private static void requireUsable(AuthenticatedUser user, AiTool tool) {
        if (!canUse(user, tool)) {
            throw BusinessException.forbidden("没有调用工具 " + tool.name() + " 的权限");
        }
    }

    private AiToolCall newCall(AuthenticatedUser user, AiToolCallRequest request, AiTool tool,
                               AiToolArguments args, LocalDateTime now) {
        AiToolCall call = new AiToolCall();
        call.setConversationId(request.conversationId());
        call.setUserId(user.userId());
        call.setToolName(tool.name());
        call.setSideEffect(tool.sideEffect());
        call.setArgumentsJson(writeJson(args.asMap()));
        call.setArgumentSummary(truncate(args.summary(), 500));
        call.setVersion(0);
        call.setStatus(AiToolCallStatus.SUCCEEDED);
        return call;
    }

    private String writeJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception ex) {
            log.warn("工具参数序列化失败，落空对象", ex);
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(AiToolCall call) {
        String json = call.getArgumentsJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.AI_TOOL_ARGUMENT_INVALID, "保存的参数无法解析，请重新发起");
        }
    }

    private AiToolCallView view(AiToolCall call, Object result) {
        if (call == null) {
            throw BusinessException.notFound("工具调用记录");
        }
        return new AiToolCallView(call.getId(), call.getToolName(), Boolean.TRUE.equals(call.getSideEffect()),
                readJson(call.getArgumentsJson()), call.getArgumentSummary(), call.getPreview(),
                call.getStatus() == null ? null : call.getStatus().name(), call.getResultSummary(),
                call.getErrorCode(), call.getErrorMessage(), call.getConfirmExpiresAt(), call.getConfirmedAt(),
                call.getExecutedAt(), call.getLatencyMs(), call.getCreatedAt(), result);
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    private void audit(AiToolCall call, String action, boolean sideEffect, String errorCode) {
        if (call == null) {
            return;
        }
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action)
                .source(AuditSource.AI_TOOL)
                .target("AI_TOOL_CALL", call.getId())
                .detail("tool", call.getToolName())
                .detail("sideEffect", sideEffect)
                .detail("status", call.getStatus() == null ? null : call.getStatus().name())
                .detail("arguments", call.getArgumentSummary());
        if (errorCode != null) {
            b.failure(errorCode);
        }
        auditService.record(b.build());
    }

    private static int toInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
