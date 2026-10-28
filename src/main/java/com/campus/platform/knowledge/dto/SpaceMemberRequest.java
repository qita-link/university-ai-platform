package com.campus.platform.knowledge.dto;

import com.campus.platform.knowledge.entity.MemberRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "添加/修改知识库成员")
public record SpaceMemberRequest(
        @Schema(description = "用户 ID") @NotNull @Positive Long userId,
        @Schema(description = "MANAGER/EDITOR/VIEWER") @NotNull MemberRole memberRole) {
}
