package com.campus.platform.knowledge.dto;

import com.campus.platform.knowledge.entity.MemberRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "修改知识库成员角色")
public record SpaceMemberRoleRequest(
        @Schema(description = "MANAGER/EDITOR/VIEWER") @NotNull MemberRole memberRole) {
}
