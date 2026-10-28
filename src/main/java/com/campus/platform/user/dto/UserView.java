package com.campus.platform.user.dto;

import com.campus.platform.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.SortedSet;

/** 用户信息（管理端）。不包含密码哈希、token_version 等内部字段。 */
@Schema(description = "用户")
public record UserView(
        Long id,
        String username,
        String displayName,
        String email,
        String phone,
        Long orgUnitId,
        String orgUnitName,
        UserStatus status,
        @Schema(description = "临时锁定截止时间（连续登录失败触发），未锁定为空") LocalDateTime lockedUntil,
        int failedLoginCount,
        SortedSet<String> roles,
        LocalDateTime lastLoginAt,
        LocalDateTime passwordChangedAt,
        @Schema(description = "乐观锁版本，修改资料时回传") int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
