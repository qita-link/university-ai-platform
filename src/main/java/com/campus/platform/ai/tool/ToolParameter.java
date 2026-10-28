package com.campus.platform.ai.tool;

/**
 * 工具参数声明。
 *
 * @param name        参数名
 * @param type        string/integer/number/boolean/datetime/enum/array
 * @param required    是否必填
 * @param description 说明（枚举类型请把可选值写进来）
 */
public record ToolParameter(String name, String type, boolean required, String description) {

    public static ToolParameter required(String name, String type, String description) {
        return new ToolParameter(name, type, true, description);
    }

    public static ToolParameter optional(String name, String type, String description) {
        return new ToolParameter(name, type, false, description);
    }
}
