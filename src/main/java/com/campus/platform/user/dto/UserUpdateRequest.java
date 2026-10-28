package com.campus.platform.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 修改用户资料（整体更新）。用户名不可修改；状态、角色、密码使用各自的专用接口。
 */
@Schema(description = "修改用户资料")
public record UserUpdateRequest(
        @NotBlank(message = "显示名不能为空") @Size(max = 64, message = "显示名最长 64 字") String displayName,
        @Email(message = "邮箱格式不正确") @Size(max = 128, message = "邮箱最长 128 字符") String email,
        @Pattern(regexp = UserValidation.PHONE_REGEX, message = UserValidation.PHONE_MESSAGE) String phone,
        @Schema(description = "所属组织 ID，为空表示不归属任何组织") @Positive Long orgUnitId,
        @Schema(description = "读取时得到的 version（乐观锁），不一致返回 409")
        @NotNull(message = "version 不能为空") @Min(0) Integer version) {
}
