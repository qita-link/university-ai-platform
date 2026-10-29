package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 排课时间规则：FORBIDDEN/FIXED 为硬约束，AVOID/PREFERRED 为软约束。
 */
@Getter
@Setter
@TableName("sch_time_rule")
public class SchTimeRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;
    private String targetType;
    private Long targetId;
    private String ruleKind;
    private Integer dayOfWeek;
    private Integer startPeriod;
    private Integer endPeriod;
    private Integer startWeek;
    private Integer endWeek;
    private Integer weight;
    private String note;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
