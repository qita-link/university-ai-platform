package com.campus.platform.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "登录请求")
public record LoginRequest(
        @Schema(description = "登录名（学号/工号）", example = "20260001")
        @NotBlank(message = "用户名不能为空") @Size(max = 64, message = "用户名过长") String username,
        @Schema(description = "密码")
        @NotBlank(message = "密码不能为空") @Size(max = 128, message = "密码过长") String password) {

    /** 避免密码随对象被打印到日志。 */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=****]";
    }
}
