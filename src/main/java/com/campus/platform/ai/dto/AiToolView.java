package com.campus.platform.ai.dto;

import com.campus.platform.ai.tool.AiTool;
import com.campus.platform.ai.tool.ToolParameter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 工具目录条目：供模型生成提示词、供前端渲染可用能力列表。
 */
@Schema(description = "AI 工具定义")
public record AiToolView(
        String name,
        String title,
        String description,
        @Schema(description = "是否写操作（调用后需确认）") boolean sideEffect,
        @Schema(description = "需要的功能权限（满足任意一个即可）") List<String> requiredPermissions,
        List<ToolParameter> parameters) {

    public static AiToolView of(AiTool tool) {
        return new AiToolView(tool.name(), tool.title(), tool.description(), tool.sideEffect(),
                tool.requiredPermissions(), tool.parameters());
    }
}
