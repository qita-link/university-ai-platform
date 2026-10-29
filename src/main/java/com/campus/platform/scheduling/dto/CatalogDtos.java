package com.campus.platform.scheduling.dto;

import com.campus.platform.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 排课基础数据 DTO：校区、教室、教师档案、行政班档案、课程库、教学班、时间规则。
 * <p>
 * 教师与行政班复用现有 {@code sys_user} / {@code sys_org_unit}，这里只暴露排课口径的扩展字段。
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    // ---------------------------------------------------------------- 校区

    @Schema(name = "SchedulingCampusView")
    public record CampusView(Long id, String code, String name, String status) {
    }

    @Schema(name = "SchedulingCampusRequest")
    public record CampusRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 16) String status) {
    }

    @Schema(name = "SchedulingCampusTravelView")
    public record TravelView(Long fromCampusId, Long toCampusId, Integer minutes) {
    }

    @Schema(name = "SchedulingCampusTravelRequest", description = "校区通行时间（无序对），会覆盖已有值")
    public record TravelRequest(@NotEmpty List<@Valid TravelItem> items) {
    }

    @Schema(name = "SchedulingCampusTravelItem")
    public record TravelItem(
            @NotNull Long fromCampusId,
            @NotNull Long toCampusId,
            @NotNull @Min(0) @Max(600) Integer minutes) {
    }

    // ---------------------------------------------------------------- 教室

    @Schema(name = "SchedulingRoomView")
    public record RoomView(Long id, String code, String name, Long campusId, String campusName, String building,
                           Integer floorNo, Integer capacity, String roomType, List<String> features, String status,
                           String note, Integer version) {
    }

    @Schema(name = "SchedulingRoomRequest",
            description = "roomType：NORMAL/MULTIMEDIA/COMPUTER_LAB/LANGUAGE_LAB/LAB/GYM/OTHER；features 为设备标签")
    public record RoomRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 64) String name,
            @NotNull Long campusId,
            @Size(max = 64) String building,
            Integer floorNo,
            @NotNull @Min(1) @Max(5000) Integer capacity,
            @NotBlank @Size(max = 32) String roomType,
            List<String> features,
            @Size(max = 16) String status,
            @Size(max = 200) String note,
            Integer version) {
    }

    @Getter
    @Setter
    @Schema(name = "SchedulingRoomQuery")
    public static class RoomQuery extends PageQuery {
        private Long campusId;
        private String roomType;
        private String status;
        private String keyword;
    }

    // ---------------------------------------------------------------- 教师

    @Schema(name = "SchedulingTeacherView", description = "教师及其排课档案；上限为空表示不限制")
    public record TeacherView(Long id, String username, String displayName, Long orgUnitId, String orgUnitName,
                              String title, Long homeCampusId, Integer maxWeeklyPeriods, Integer maxDailyPeriods,
                              Integer maxDailySessions, Integer maxConsecutivePeriods, List<Long> courseIds) {
    }

    @Schema(name = "SchedulingTeacherProfileRequest")
    public record TeacherProfileRequest(
            Long orgUnitId,
            @Size(max = 32) String title,
            Long homeCampusId,
            @Min(1) @Max(112) Integer maxWeeklyPeriods,
            @Min(1) @Max(16) Integer maxDailyPeriods,
            @Min(1) @Max(16) Integer maxDailySessions,
            @Min(1) @Max(16) Integer maxConsecutivePeriods,
            @Size(max = 200) String note,
            List<Long> courseIds) {
    }

    @Schema(name = "SchedulingTeacherWorkloadView", description = "教师工作量统计（按已发布或指定版本）")
    public record TeacherWorkloadView(Long teacherId, String teacherName, int versions, int sessions, int periods,
                                      int days, List<WorkloadItem> items) {
    }

    @Schema(name = "SchedulingWorkloadItem")
    public record WorkloadItem(Long versionId, Long semesterId, String semesterName, int sessions, int periods) {
    }

    // ---------------------------------------------------------------- 行政班

    @Schema(name = "SchedulingClassView")
    public record ClassView(Long id, String code, String name, Long parentId, String grade, Integer studentCount,
                            Long homeCampusId, Integer maxDailyPeriods, Integer maxConsecutivePeriods) {
    }

    @Schema(name = "SchedulingClassProfileRequest")
    public record ClassProfileRequest(
            @NotNull @Min(0) @Max(5000) Integer studentCount,
            @Size(max = 16) String grade,
            Long homeCampusId,
            @Min(1) @Max(16) Integer maxDailyPeriods,
            @Min(1) @Max(16) Integer maxConsecutivePeriods) {
    }

    // ---------------------------------------------------------------- 课程库

    @Schema(name = "SchedulingCourseView")
    public record CourseView(Long id, String code, String name, String courseType, BigDecimal credit,
                             Integer totalHours, Long orgUnitId, String defaultRoomType, List<String> requiredFeatures,
                             Integer priority, String status, String description, Integer version) {
    }

    @Schema(name = "SchedulingCourseRequest")
    public record CourseRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 16) String courseType,
            @NotNull @DecimalMin("0.0") @DecimalMax("20.0") BigDecimal credit,
            @NotNull @Min(1) @Max(2000) Integer totalHours,
            Long orgUnitId,
            @Size(max = 32) String defaultRoomType,
            List<String> requiredFeatures,
            @Min(1) @Max(10) Integer priority,
            @Size(max = 16) String status,
            @Size(max = 500) String description,
            Integer version) {
    }

    // ---------------------------------------------------------------- 教学班

    @Schema(name = "SchedulingTeachingClassView")
    public record TeachingClassView(Long id, Long semesterId, String code, String name, Integer studentCount,
                                    Integer computedStudentCount, List<MemberView> members) {
    }

    @Schema(name = "SchedulingTeachingClassMemberView")
    public record MemberView(Long classId, String className, boolean fullClass, Integer studentCount) {
    }

    @Schema(name = "SchedulingTeachingClassRequest",
            description = "fullClass=true 表示整班参加；false 表示选修抽取，studentCount 必填")
    public record TeachingClassRequest(
            @NotNull Long semesterId,
            @NotBlank @Size(max = 48) String code,
            @NotBlank @Size(max = 128) String name,
            Integer studentCount,
            @NotEmpty List<@Valid MemberItem> members) {
    }

    @Schema(name = "SchedulingTeachingClassMemberItem")
    public record MemberItem(
            @NotNull Long classId,
            @NotNull Boolean fullClass,
            Integer studentCount) {
    }

    // ---------------------------------------------------------------- 时间规则

    @Schema(name = "SchedulingTimeRuleView",
            description = "targetType：GLOBAL/TEACHER/CLASS/TEACHING_CLASS/ROOM/COURSE/TASK；"
                    + "kind：FORBIDDEN/FIXED/AVOID/PREFERRED")
    public record TimeRuleView(Long id, Long semesterId, String targetType, Long targetId, String targetName,
                               String kind, Integer dayOfWeek, Integer startPeriod, Integer endPeriod,
                               Integer startWeek, Integer endWeek, Integer weight, String note, List<Integer> weeks,
                               String timeText) {
    }

    @Schema(name = "SchedulingTimeRuleRequest")
    public record TimeRuleRequest(
            @NotNull Long semesterId,
            @NotBlank @Size(max = 16) String targetType,
            Long targetId,
            @NotBlank @Size(max = 16) String kind,
            @Min(1) @Max(7) Integer dayOfWeek,
            @NotNull @Min(1) @Max(16) Integer startPeriod,
            @NotNull @Min(1) @Max(16) Integer endPeriod,
            @Min(1) @Max(30) Integer startWeek,
            @Min(1) @Max(30) Integer endWeek,
            List<Integer> weeks,
            @Min(1) @Max(10) Integer weight,
            @Size(max = 200) String note) {
    }
}
