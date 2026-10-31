package com.campus.platform.setting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "平台设置项")
public record SettingView(
        String key,
        String value,
        String description) {
}
