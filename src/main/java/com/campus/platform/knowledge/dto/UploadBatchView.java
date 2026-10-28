package com.campus.platform.knowledge.dto;

import com.campus.platform.knowledge.entity.BatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Markdown 批量上传结果")
public record UploadBatchView(
        Long batchId,
        Long spaceId,
        int totalCount,
        int successCount,
        int failedCount,
        BatchStatus status,
        List<UploadItemResult> items) {
}
