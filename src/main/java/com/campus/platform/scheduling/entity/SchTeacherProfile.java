package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 教师排课档案：工作量上限、连续上课上限、所属学院（扩展 sys_user）。
 */
@Getter
@Setter
@TableName("sch_teacher_profile")
public class SchTeacherProfile {

    @TableId(value = "teacher_id", type = IdType.INPUT)
    private Long teacherId;

    private Long orgUnitId;
    private String title;
    private Long homeCampusId;
    private Integer maxWeeklyPeriods;
    private Integer maxDailyPeriods;
    private Integer maxDailySessions;
    private Integer maxConsecutivePeriods;
    private String note;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
