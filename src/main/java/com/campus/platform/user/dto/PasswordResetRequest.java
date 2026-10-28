package com.campus.platform.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "管理员重置密码")
public record PasswordResetRequest(
        @Schema(description = "新密码，需满足密码强度要求。重置后该用户全部令牌失效")
        @NotBlank(message = "新密码不能为空") @Size(max = 128, message = "密码过长") String newPassword) {

    @Override
    public String toString() {
        return "PasswordResetRequest[newPassword=****]";
    }
}
