package com.campus.platform.user.dto;

import com.campus.platform.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "变更账号状态")
public record UserStatusRequest(
        @Schema(description = "ACTIVE 启用（同时解除临时锁定）/ DISABLED 停用 / LOCKED 管理员锁定")
        @NotNull(message = "状态不能为空") UserStatus status,
        @Schema(description = "原因，写入审计") @Size(max = 200, message = "原因最长 200 字") String reason) {
}
