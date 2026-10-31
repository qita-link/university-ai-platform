package com.campus.platform.grade.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(description = "批量保存成绩（草稿）")
public record GradeEntryRequest(
        @NotEmpty(message = "至少提交一条成绩")
        @Valid List<GradeEntryItem> items) {
}
