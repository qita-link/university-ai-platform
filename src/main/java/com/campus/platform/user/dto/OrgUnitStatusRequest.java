package com.campus.platform.user.dto;

import com.campus.platform.user.entity.OrgStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "启用/停用组织单元")
public record OrgUnitStatusRequest(@NotNull(message = "状态不能为空") OrgStatus status) {
}
