package com.campus.platform.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "刷新令牌请求")
public record RefreshTokenRequest(
        @Schema(description = "登录或上次刷新时返回的 refreshToken") @NotBlank(message = "refreshToken 不能为空")
        String refreshToken) {
}
