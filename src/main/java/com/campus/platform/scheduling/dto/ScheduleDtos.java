package com.campus.platform.scheduling.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 排课版本、课表查询、调整、发布与导出 DTO。
 */
public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    @Schema(name = "SchedulingVersionView")
    public record VersionView(
            Long id,
            Long semesterId,
            String semesterName,
            Integer versionNo,
            String name,
            String source,
            Long jobId,
            Long baseVersionId,
            String status,
            Integer totalSessions,
            Integer placedSessions,
            Integer hardViolations,
            BigDecimal score,
            Long softPenalty,
            LocalDateTime publishedAt,
            Long publishedBy,
            LocalDateTime createdAt,
            Integer version,
            String note) {
    }

    @Schema(name = "SchedulingEntryView", description = "一节（每周重复一次）课时安排")
    public record EntryView(
            Long id,
            Long taskId,
            String taskCode,
            String courseName,
            String teachingClassName,
            List<String> classNames,
            List<String> teacherNames,
            Long roomId,
            String roomCode,
            String campusName,
            Integer dayOfWeek,
            Integer startPeriod,
            Integer endPeriod,
            Boolean locked,
            String origin,
            List<Integer> weeks,
            String weekText,
            String timeText) {
    }

    @Schema(name = "SchedulingTimetableRow")
    public record TimetableRow(Integer periodNo, LocalTime startTime, LocalTime endTime, List<TimetableCell> cells) {
    }

    @Schema(name = "SchedulingTimetableCell")
    public record TimetableCell(Integer dayOfWeek, List<EntryView> entries) {
    }

    @Schema(name = "SchedulingTimetableView", description = "按星期 × 节次组织的课表")
    public record TimetableView(Long versionId, String versionName, String scope, String ownerName,
                                List<Integer> days, List<TimetableRow> rows) {
    }

    @Schema(name = "SchedulingUnplacedView")
    public record UnplacedView(Long taskId, String taskCode, String courseName, String teachingClassName,
                               int sessionsPerWeek, int placedSessions, int missingSessions, String reason) {
    }

    @Schema(name = "SchedulingConflictView")
    public record ConflictView(String code, String label, String severity, Long taskId, String taskCode,
                               Long otherTaskId, Integer dayOfWeek, Integer startPeriod, Integer endPeriod,
                               List<String> teacherNames, List<String> classNames, List<String> roomCodes,
                               String message, String suggestion) {
    }

    @Schema(name = "SchedulingSoftScoreView")
    public record SoftScoreView(String code, String label, int weight, long applicable, long violated,
                                long penalty, double satisfaction) {
    }

    @Schema(name = "SchedulingEvaluationView")
    public record EvaluationView(double score, int placedSessions, int totalSessions, int hardViolations,
                                 long softPenalty, List<SoftScoreView> details, List<ConflictView> conflicts) {
    }

    @Schema(name = "SchedulingEntryDiff")
    public record EntryDiff(Long taskId, String taskCode, String courseName, Integer sessionIndex,
                            String from, String to, String change) {
    }

    @Schema(name = "SchedulingCompareView")
    public record CompareView(Long versionA, Long versionB, String nameA, String nameB, int added, int removed,
                              int moved, int unchanged, List<EntryDiff> diffs) {
    }

    @Schema(name = "SchedulingAdjustmentView")
    public record AdjustmentView(Long id, Long versionId, Long assignmentId, String action, String fromText,
                                 String toText, String reason, Long operatorId, LocalDateTime createdAt) {
    }

    @Schema(name = "SchedulingAdjustRequest", description = "移动单节课；dayOfWeek 为空表示取消安排（unplace）")
    public record AdjustRequest(
            @Min(1) @Max(7) Integer dayOfWeek,
            @Min(1) @Max(16) Integer startPeriod,
            Long roomId,
            @Size(max = 500) String reason,
            Boolean force) {
    }

    @Schema(name = "SchedulingLockRequest")
    public record LockRequest(@NotNull Boolean locked, @Size(max = 500) String reason) {
    }

    @Schema(name = "SchedulingCopyRequest")
    public record CopyRequest(@Size(max = 128) String name, @Size(max = 500) String reason) {
    }

    @Schema(name = "SchedulingPublishRequest")
    public record PublishRequest(@Size(max = 500) String reason) {
    }

    @Schema(name = "SchedulingPublishRecordView")
    public record PublishRecordView(Long id, Long semesterId, Long versionId, String versionName, String action,
                                    Long operatorId, String operatorName, String reason, LocalDateTime createdAt) {
    }

    @Schema(name = "SchedulingCandidateView", description = "某节课的可选位置（已排除与现有课表冲突的组合）")
    public record CandidateView(Integer dayOfWeek, Integer startPeriod, Integer endPeriod, Long roomId,
                                String roomCode, boolean available, List<String> conflicts) {
    }

    @Schema(name = "SchedulingVersionCreateRequest", description = "以某版本为基线复制出新草稿")
    public record VersionCreateRequest(@NotNull Long sourceVersionId, @Size(max = 128) String name) {
    }
}
