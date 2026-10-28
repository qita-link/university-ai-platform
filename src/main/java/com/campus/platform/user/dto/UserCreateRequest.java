package com.campus.platform.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Set;

@Schema(description = "创建用户")
public record UserCreateRequest(
        @Schema(description = "登录名（学号/工号）", example = "20260001")
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = UserValidation.USERNAME_REGEX, message = UserValidation.USERNAME_MESSAGE)
        String username,
        @Schema(description = "显示名")
        @NotBlank(message = "显示名不能为空") @Size(max = 64, message = "显示名最长 64 字") String displayName,
        @Schema(description = "初始密码，需满足密码强度要求")
        @NotBlank(message = "初始密码不能为空") @Size(max = 128, message = "密码过长") String password,
        @Schema(description = "邮箱，可空，全局唯一")
        @Email(message = "邮箱格式不正确") @Size(max = 128, message = "邮箱最长 128 字符") String email,
        @Schema(description = "手机号，可空")
        @Pattern(regexp = UserValidation.PHONE_REGEX, message = UserValidation.PHONE_MESSAGE) String phone,
        @Schema(description = "所属组织 ID，可空") @Positive Long orgUnitId,
        @Schema(description = "角色编码，至少一个，如 [\"STUDENT\"]；需要 role:manage")
        @NotEmpty(message = "至少分配一个角色") @Size(max = 10, message = "角色过多")
        Set<@NotBlank @Pattern(regexp = UserValidation.ROLE_CODE_REGEX, message = "角色编码格式错误") String> roleCodes) {

    @Override
    public String toString() {
        return "UserCreateRequest[username=" + username + ", password=****, roles=" + roleCodes + "]";
    }
}
