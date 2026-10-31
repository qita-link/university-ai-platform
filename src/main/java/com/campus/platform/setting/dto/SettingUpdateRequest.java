package com.campus.platform.setting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "修改设置项")
public record SettingUpdateRequest(
        @NotBlank(message = "设置值不能为空")
        @Size(max = 255, message = "设置值过长")
        String value) {
}
