package com.campus.platform.ai.tool;

/**
 * 工具执行结果。
 *
 * @param data    返回给调用方的业务数据（只回本次请求，不落库）
 * @param summary 可读摘要（落库供追溯，不含大字段）
 */
public record ToolResult(Object data, String summary) {
}
