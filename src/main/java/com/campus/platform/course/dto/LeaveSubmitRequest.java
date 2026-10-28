package com.campus.platform.course.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Schema(description = "提交请假")
public record LeaveSubmitRequest(
        @Schema(description = "课程 ID，必须是本人已选课程") @NotNull @Positive Long courseId,
        @Schema(description = "排课 ID，可空；填写时必须是该课程的排课") @Positive Long scheduleId,
        @Schema(description = "请假开始时间") @NotNull LocalDateTime startAt,
        @Schema(description = "请假结束时间") @NotNull LocalDateTime endAt,
        @Schema(description = "请假原因") @NotBlank @Size(max = 500) String reason) {
}
