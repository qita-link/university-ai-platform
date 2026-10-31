package com.campus.platform.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.SortedSet;

@Schema(description = "当前登录用户")
public record CurrentUserResponse(
        @Schema(description = "用户 ID") Long userId,
        @Schema(description = "登录名（学号/工号）") String username,
        @Schema(description = "显示名") String displayName,
        @Schema(description = "头像版本；0 表示没有头像，前端拼进头像 URL 做缓存失效") int avatarVersion,
        @Schema(description = "所属组织单元 ID") Long orgUnitId,
        @Schema(description = "角色编码") SortedSet<String> roles,
        @Schema(description = "权限点编码") SortedSet<String> permissions) {
}
