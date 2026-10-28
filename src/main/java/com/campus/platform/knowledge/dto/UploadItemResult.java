package com.campus.platform.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "批量上传中的单个文件结果")
public record UploadItemResult(
        String filename,
        boolean success,
        Long documentId,
        String title,
        String error) {
}
