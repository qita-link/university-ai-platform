package com.campus.platform.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "修改本人密码")
public record ChangePasswordRequest(
        @Schema(description = "原密码")
        @NotBlank(message = "原密码不能为空") @Size(max = 128, message = "原密码过长") String oldPassword,
        @Schema(description = "新密码：至少 8 位，包含小写字母、大写字母、数字、符号中的 3 类，不能包含用户名")
        @NotBlank(message = "新密码不能为空") @Size(max = 128, message = "新密码过长") String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[oldPassword=****, newPassword=****]";
    }
}
