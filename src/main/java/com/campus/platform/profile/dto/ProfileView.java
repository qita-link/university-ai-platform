package com.campus.platform.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.SortedSet;

@Schema(description = "个人资料")
public record ProfileView(
        @Schema(description = "用户 ID") Long userId,
        @Schema(description = "登录名（学号/工号）") String username,
        @Schema(description = "姓名") String displayName,
        @Schema(description = "邮箱") String email,
        @Schema(description = "手机号") String phone,
        @Schema(description = "所属组织 ID（学院/班级）") Long orgUnitId,
        @Schema(description = "所属组织名称") String orgUnitName,
        @Schema(description = "角色编码") SortedSet<String> roles,
        @Schema(description = "头像版本；0 表示没有头像") int avatarVersion,
        @Schema(description = "最近登录时间") LocalDateTime lastLoginAt,
        @Schema(description = "账号创建时间") LocalDateTime createdAt) {
}
