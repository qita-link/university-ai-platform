package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 每日节次作息。section 相同且编号连续的节次才能连堂。
 */
@Getter
@Setter
@TableName("sch_period")
public class SchPeriod {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;
    private Integer periodNo;
    private LocalTime startTime;
    private LocalTime endTime;
    private String section;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
