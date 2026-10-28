package com.campus.platform.ai.tool;

import com.campus.platform.security.AuthenticatedUser;

import java.util.List;

/**
 * 一个可供 AI 调用的工具。
 * <p>
 * 工具只负责“参数校验 + 调用既有 Service”，不实现业务规则，也不自行判断数据归属：
 * 权限与归属一律由被调用的 Service 依据 {@link AuthenticatedUser} 判断，AI 无法绕过。
 * <p>
 * {@link #sideEffect()} 为 true 的工具不会在调用时执行，而是生成待确认记录；
 * 确认后由同一份参数执行，保证“用户看到的预览”与“实际执行”一致。
 */
public interface AiTool {

    /** 工具名，模型与调用方使用；全局唯一，形如 getMySchedule。 */
    String name();

    /** 面向用户的短标题。 */
    String title();

    /** 面向模型的能力说明，写清能做什么、需要什么参数、有什么限制。 */
    String description();

    /** 是否写操作（需要用户确认）。 */
    boolean sideEffect();

    /** 需要的功能权限（满足任意一个即可）；为空表示只需要 ai:tool:use。 */
    List<String> requiredPermissions();

    /** 参数声明，用于生成模型提示词与前端表单。 */
    List<ToolParameter> parameters();

    /** 写操作的“目标与影响”预览，用于确认页展示；只读工具返回 null。 */
    ToolPreview preview(AuthenticatedUser user, AiToolArguments args);

    /** 实际执行；返回结果与可读摘要（摘要落库，结果只回给本次请求）。 */
    ToolResult execute(AuthenticatedUser user, AiToolArguments args);
}
