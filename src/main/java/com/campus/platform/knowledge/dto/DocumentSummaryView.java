package com.campus.platform.knowledge.dto;

import com.campus.platform.knowledge.entity.DocumentSource;
import com.campus.platform.knowledge.entity.DocumentStatus;
import com.campus.platform.knowledge.entity.ProcessStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "知识库文档摘要")
public record DocumentSummaryView(
        Long id,
        Long spaceId,
        Long batchId,
        String title,
        String originalFilename,
        DocumentSource sourceType,
        String sourcePath,
        DocumentStatus status,
        ProcessStatus processStatus,
        String processError,
        String contentHash,
        Long currentVersionId,
        Integer versionNo,
        Integer sizeBytes,
        long chunkCount,
        Long uploadedBy,
        String uploadedByName,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
