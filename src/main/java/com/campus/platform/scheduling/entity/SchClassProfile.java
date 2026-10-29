package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 行政班排课档案（扩展 sys_org_unit type=CLASS）。
 */
@Getter
@Setter
@TableName("sch_class_profile")
public class SchClassProfile {

    @TableId(value = "org_unit_id", type = IdType.INPUT)
    private Long orgUnitId;

    private Integer studentCount;
    private String grade;
    private Long homeCampusId;
    private Integer maxDailyPeriods;
    private Integer maxConsecutivePeriods;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
