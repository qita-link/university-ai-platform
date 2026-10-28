package com.campus.platform.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "文档版本摘要")
public record VersionView(
        Long id,
        int versionNo,
        String contentHash,
        int sizeBytes,
        String changeNote,
        Long createdBy,
        String createdByName,
        LocalDateTime createdAt) {
}
