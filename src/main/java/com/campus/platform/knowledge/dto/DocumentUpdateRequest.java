package com.campus.platform.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Schema(description = "在线修改 Markdown 文档（生成新版本）")
public record DocumentUpdateRequest(
        @Schema(description = "标题") @NotBlank @Size(max = 255) String title,
        @Schema(description = "Markdown 原文") @NotBlank String content,
        @Schema(description = "变更说明") @Size(max = 255) String changeNote,
        @Schema(description = "生效开始时间，可空") LocalDateTime effectiveFrom,
        @Schema(description = "生效结束时间，可空") LocalDateTime effectiveTo,
        @Schema(description = "读取时得到的 version") @NotNull @Min(0) Integer version) {
}
