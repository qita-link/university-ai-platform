package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学期排课配置：教学周数、第 1 周周一、教学日、每日节数、默认通行时间。
 */
@Getter
@Setter
@TableName("sch_semester_config")
public class SchSemesterConfig {

    @TableId(value = "semester_id", type = IdType.INPUT)
    private Long semesterId;

    private Integer teachingWeeks;
    private LocalDate firstWeekStart;
    private String teachingDays;
    private Integer periodsPerDay;
    private Integer defaultTravelMinutes;
    private Integer maxRoomCandidates;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
