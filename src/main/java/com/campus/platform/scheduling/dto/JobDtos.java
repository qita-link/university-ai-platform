package com.campus.platform.scheduling.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 排课任务（一次求解）DTO。
 * <p>
 * 状态说明见 {@code SolverStatus}：DRAFT/QUEUED/RUNNING/SUCCEEDED/PARTIAL/INFEASIBLE/UNKNOWN/FAILED/CANCELLED。
 * {@code solverStatus} 保留 CP-SAT 原始状态（OPTIMAL/FEASIBLE/INFEASIBLE/UNKNOWN/MODEL_INVALID）。
 */
public final class JobDtos {

    private JobDtos() {
    }

    @Schema(name = "SchedulingJobCreateRequest", description = "创建排课任务；不传 taskIds 表示整个学期（或指定学院）")
    public record JobCreateRequest(
            @NotNull Long semesterId,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 16) String mode,
            Long baseVersionId,
            List<Long> taskIds,
            Long orgUnitId,
            @Min(1) @Max(3600) Integer timeLimitSeconds,
            @Min(1) @Max(32) Integer workers,
            Integer randomSeed,
            Boolean allowPartial,
            Boolean keepLocked,
            Map<String, Integer> weightOverrides,
            @Schema(description = "局部重排：仅重新安排这些教学任务，其余课次固定（mode=INCREMENTAL）")
            List<Long> replanTaskIds) {
    }

    @Schema(name = "SchedulingJobView")
    public record JobView(
            Long id,
            Long semesterId,
            String semesterName,
            String name,
            String mode,
            Long baseVersionId,
            String status,
            String solverStatus,
            Integer progress,
            String phase,
            Long objective,
            Long bestBound,
            Long resultVersionId,
            String errorCode,
            String message,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Long elapsedMillis,
            Integer taskCount,
            Integer totalSessions,
            String statsJson) {
    }

    @Schema(name = "SchedulingJobStatusView")
    public record JobStatusView(Long id, String status, String phase, int progress, long elapsedMillis,
                                String solverStatus, Long objective, Long bestBound, String message) {
    }

    @Schema(name = "SchedulingJobLogView")
    public record JobLogView(String level, String message, LocalDateTime createdAt) {
    }

    @Schema(name = "SchedulingDiagnosticsView", description = "任务诊断：预检问题 + 未安排原因 + 资源摘要")
    public record DiagnosticsView(Long jobId, String status, List<TaskDtos.IssueView> issues, String summary) {
    }
}
