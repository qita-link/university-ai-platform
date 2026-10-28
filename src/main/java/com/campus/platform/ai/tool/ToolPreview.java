package com.campus.platform.ai.tool;

/** 待确认操作的目标与影响（纯文本，已脱敏，不含敏感数据）。 */
public record ToolPreview(String summary) {
}
