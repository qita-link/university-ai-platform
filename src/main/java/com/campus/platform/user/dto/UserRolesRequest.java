package com.campus.platform.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

@Schema(description = "分配角色（整体替换）")
public record UserRolesRequest(
        @Schema(description = "角色编码集合，可为空集合（用户将只能访问 /auth/me 等无需权限点的接口）")
        @NotNull(message = "roleCodes 不能为空") @Size(max = 10, message = "角色过多")
        Set<@NotBlank @Pattern(regexp = UserValidation.ROLE_CODE_REGEX, message = "角色编码格式错误") String> roleCodes) {
}
