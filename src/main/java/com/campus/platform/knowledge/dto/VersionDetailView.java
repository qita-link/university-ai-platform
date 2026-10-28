package com.campus.platform.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "文档版本详情")
public record VersionDetailView(
        Long id,
        Long documentId,
        int versionNo,
        String content,
        String contentHash,
        int sizeBytes,
        String changeNote,
        Long createdBy,
        String createdByName,
        LocalDateTime createdAt) {
}
