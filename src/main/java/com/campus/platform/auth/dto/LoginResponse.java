package com.campus.platform.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "登录结果")
public record LoginResponse(
        @Schema(description = "访问令牌，放在 Authorization: Bearer <accessToken> 中") String accessToken,
        @Schema(description = "令牌类型", example = "Bearer") String tokenType,
        @Schema(description = "有效秒数") long expiresIn,
        @Schema(description = "过期时间") Instant expiresAt,
        @Schema(description = "当前用户") CurrentUserResponse user) {

    public static final String BEARER = "Bearer";

    /** 令牌不出现在日志中 */
    @Override
    public String toString() {
        return "LoginResponse[accessToken=****, expiresAt=" + expiresAt + ", user=" + user.username() + "]";
    }
}
