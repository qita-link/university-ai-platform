package com.campus.platform.ai.tool;

import com.campus.platform.security.AuthenticatedUser;

import java.util.List;
import java.util.function.BiFunction;

/**
 * 用函数式方式声明一个工具，避免为每个工具单独建类。
 * <p>
 * 只读工具不需要 {@code preview}（传 null）；写工具必须提供 {@code preview}，
 * 否则确认页无法展示“将要做什么”，因此 {@link #preview} 在缺失时返回说明性文本。
 */
public record SimpleAiTool(
        String name,
        String title,
        String description,
        boolean sideEffect,
        List<String> requiredPermissions,
        List<ToolParameter> parameters,
        BiFunction<AuthenticatedUser, AiToolArguments, ToolPreview> previewFn,
        BiFunction<AuthenticatedUser, AiToolArguments, ToolResult> executeFn) implements AiTool {

    public SimpleAiTool {
        requiredPermissions = requiredPermissions == null ? List.of() : List.copyOf(requiredPermissions);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    public static SimpleAiTool readOnly(String name, String title, String description,
                                        List<String> requiredPermissions, List<ToolParameter> parameters,
                                        BiFunction<AuthenticatedUser, AiToolArguments, ToolResult> executeFn) {
        return new SimpleAiTool(name, title, description, false, requiredPermissions, parameters, null, executeFn);
    }

    public static SimpleAiTool write(String name, String title, String description,
                                     List<String> requiredPermissions, List<ToolParameter> parameters,
                                     BiFunction<AuthenticatedUser, AiToolArguments, ToolPreview> previewFn,
                                     BiFunction<AuthenticatedUser, AiToolArguments, ToolResult> executeFn) {
        return new SimpleAiTool(name, title, description, true, requiredPermissions, parameters, previewFn, executeFn);
    }

    @Override
    public ToolPreview preview(AuthenticatedUser user, AiToolArguments args) {
        if (!sideEffect) {
            return null;
        }
        return previewFn == null
                ? new ToolPreview("将执行 " + title)
                : previewFn.apply(user, args);
    }

    @Override
    public ToolResult execute(AuthenticatedUser user, AiToolArguments args) {
        return executeFn.apply(user, args);
    }
}
