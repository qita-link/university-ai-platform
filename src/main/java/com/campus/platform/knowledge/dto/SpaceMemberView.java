package com.campus.platform.knowledge.dto;

import com.campus.platform.knowledge.entity.MemberRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "知识库成员")
public record SpaceMemberView(
        Long userId,
        String username,
        String displayName,
        MemberRole memberRole,
        Long grantedBy,
        LocalDateTime createdAt) {
}
