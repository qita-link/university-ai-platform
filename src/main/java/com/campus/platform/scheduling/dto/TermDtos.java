package com.campus.platform.scheduling.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 学期、教学日历与约束配置相关的 DTO。
 * <p>
 * 学期本身复用 {@code semester} 表（阶段六课程模块），这里只补充排课口径的配置，
 * 不重复创建学期实体。
 */
public final class TermDtos {

    private TermDtos() {
    }

    @Schema(name = "SchedulingTermView", description = "学期及其排课配置")
    public record TermView(
            Long id,
            String code,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            ConfigView config) {
    }

    @Schema(name = "SchedulingTermCreateRequest", description = "新建学期并同时建立排课配置")
    public record TermCreateRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 64) String name,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotNull @Min(1) @Max(30) Integer teachingWeeks,
            @NotNull LocalDate firstWeekStart,
            @NotEmpty List<@Min(1) @Max(7) Integer> teachingDays,
            @NotNull @Min(1) @Max(16) Integer periodsPerDay) {
    }

    @Schema(name = "SchedulingTermConfigRequest", description = "学期排课配置")
    public record ConfigRequest(
            @NotNull @Min(1) @Max(30) Integer teachingWeeks,
            @NotNull LocalDate firstWeekStart,
            @NotEmpty List<@Min(1) @Max(7) Integer> teachingDays,
            @NotNull @Min(1) @Max(16) Integer periodsPerDay,
            @Min(0) @Max(600) Integer defaultTravelMinutes,
            @Min(1) @Max(200) Integer maxRoomCandidates) {
    }

    @Schema(name = "SchedulingTermConfigView")
    public record ConfigView(
            Integer teachingWeeks,
            LocalDate firstWeekStart,
            List<Integer> teachingDays,
            Integer periodsPerDay,
            Integer defaultTravelMinutes,
            Integer maxRoomCandidates,
            Integer periodCount,
            boolean configured) {
    }

    @Schema(name = "SchedulingPeriodView", description = "节次作息；section 相同且编号连续才能连堂")
    public record PeriodView(
            @Min(1) @Max(16) Integer periodNo,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotBlank String section) {
    }

    @Schema(name = "SchedulingPeriodRequest", description = "整体替换某学期的节次作息")
    public record PeriodRequest(@NotEmpty List<@Valid PeriodView> periods) {
    }

    @Schema(name = "SchedulingWeekView", description = "特殊教学周")
    public record WeekView(
            @Min(1) @Max(30) Integer weekNo,
            @NotBlank String weekType,
            Boolean teachingAllowed,
            String note) {
    }

    @Schema(name = "SchedulingDateView", description = "特殊日期：HOLIDAY/SUSPENDED/WORKDAY/MAKEUP")
    public record DateView(
            @NotNull LocalDate eventDate,
            @NotBlank String dateType,
            @Min(1) @Max(7) Integer followDayOfWeek,
            LocalDate followDate,
            @NotBlank String name) {
    }

    @Schema(name = "SchedulingCalendarView", description = "教学日历：特殊教学周 + 特殊日期")
    public record CalendarView(List<WeekView> weeks, List<DateView> dates) {
    }

    @Schema(name = "SchedulingCalendarRequest", description = "整体替换教学日历")
    public record CalendarRequest(
            List<@Valid WeekView> weeks,
            List<@Valid DateView> dates) {
    }

    @Schema(name = "SchedulingConstraintView", description = "约束项：硬约束不可关闭，软约束可配权重")
    public record ConstraintView(
            String code,
            String label,
            boolean hard,
            boolean enabled,
            int weight,
            int defaultWeight) {
    }

    @Schema(name = "SchedulingConstraintRequest", description = "覆盖软约束权重（未提交的项保持原值）")
    public record ConstraintRequest(List<@Valid ConstraintItem> items) {
    }

    @Schema(name = "SchedulingConstraintItem")
    public record ConstraintItem(
            @NotBlank String code,
            @NotNull Boolean enabled,
            @NotNull @Min(0) @Max(1000) Integer weight) {
    }

    @Schema(name = "SchedulingTeacherAvailability",
            description = "教师时间要求：允许来校的星期与半天；两天/两段都为空表示不作限制")
    public record TeacherAvailabilityView(
            long semesterId,
            long teacherId,
            String teacherName,
            /** 允许的星期 1..7；空表示不限 */
            List<Integer> days,
            /** 允许的半天 MORNING/AFTERNOON/EVENING；空表示不限 */
            List<String> sections) {
    }

    @Schema(name = "SchedulingTeacherAvailabilityRequest")
    public record TeacherAvailabilityRequest(
            @Schema(description = "允许的星期 1..7；空表示不限") List<Integer> days,
            @Schema(description = "允许的半天 MORNING/AFTERNOON/EVENING；空表示不限") List<String> sections) {
    }
}
