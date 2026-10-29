package com.campus.platform.scheduling.dto;

import com.campus.platform.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 教学任务（每学期某课程由某教师面向某教学班开设的具体任务）与预检 DTO。
 */
public final class TaskDtos {

    private TaskDtos() {
    }

    @Schema(name = "SchedulingTeacherRef")
    public record TeacherRef(Long teacherId, String teacherName, String role) {
    }

    @Schema(name = "SchedulingTaskRoomRef", description = "REQUIRED/PREFERRED/FORBIDDEN")
    public record RoomRef(Long roomId, String roomCode, String mode) {
    }

    @Schema(name = "SchedulingTeachingTaskView")
    public record TaskView(
            Long id,
            Long semesterId,
            String code,
            Long courseId,
            String courseCode,
            String courseName,
            Long teachingClassId,
            String teachingClassName,
            List<String> classNames,
            Long orgUnitId,
            Integer startWeek,
            Integer endWeek,
            String weekParity,
            List<Integer> weeks,
            Integer sessionsPerWeek,
            Integer periodsPerSession,
            Integer totalHours,
            Integer providedHours,
            boolean hoursConsistent,
            Boolean roomRequired,
            String roomType,
            List<String> requiredFeatures,
            Integer priority,
            Boolean allowSameDay,
            Boolean uniformStart,
            String status,
            String note,
            List<TeacherRef> teachers,
            List<RoomRef> rooms,
            Integer version) {
    }

    @Schema(name = "SchedulingTeachingTaskRequest")
    public record TaskRequest(
            @NotNull Long semesterId,
            @NotBlank @Size(max = 48) String code,
            @NotNull Long courseId,
            @NotNull Long teachingClassId,
            Long orgUnitId,
            @NotNull @Min(1) @Max(30) Integer startWeek,
            @NotNull @Min(1) @Max(30) Integer endWeek,
            @Size(max = 8) String weekParity,
            @NotNull @Min(1) @Max(14) Integer sessionsPerWeek,
            @NotNull @Min(1) @Max(8) Integer periodsPerSession,
            Integer totalHours,
            Boolean roomRequired,
            @Size(max = 32) String roomType,
            List<String> requiredFeatures,
            @Min(1) @Max(10) Integer priority,
            Boolean allowSameDay,
            Boolean uniformStart,
            Long linkedCourseId,
            @Size(max = 16) String status,
            @Size(max = 200) String note,
            @NotEmpty List<@NotNull Long> teacherIds,
            List<@Valid RoomRef> rooms,
            Integer version) {
    }

    @Getter
    @Setter
    @Schema(name = "SchedulingTaskQuery")
    public static class TaskQuery extends PageQuery {
        private Long semesterId;
        private Long orgUnitId;
        private Long courseId;
        private Long teachingClassId;
        private Long teacherId;
        private String status;
        private String keyword;
    }

    @Schema(name = "SchedulingImportRowError")
    public record ImportRowError(int line, String code, String message) {
    }

    @Schema(name = "SchedulingImportResult")
    public record ImportResult(int total, int created, int updated, int failed, List<ImportRowError> errors) {
    }

    @Schema(name = "SchedulingIssueView", description = "预检/诊断问题：severity=ERROR 阻止求解，WARNING 提示")
    public record IssueView(String severity, String code, Long taskId, String message) {
    }

    @Schema(name = "SchedulingPrecheckView")
    public record PrecheckView(int errorCount, int warningCount, boolean solvable, String summary,
                               List<IssueView> issues) {
    }

    @Schema(name = "SchedulingTeacherPreferenceRequest", description = "教师维护本人的偏好/回避（软约束）")
    public record TeacherPreferenceRequest(
            @NotNull Long semesterId,
            @NotNull Integer dayOfWeek,
            @NotNull @Min(1) @Max(16) Integer startPeriod,
            @NotNull @Min(1) @Max(16) Integer endPeriod,
            Boolean preferred) {
    }
}
