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
 * 教学日历特殊日期：节假日、停课、调休上课、补课。
 */
@Getter
@Setter
@TableName("sch_calendar_date")
public class SchCalendarDate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;
    private LocalDate eventDate;
    private String dateType;
    private Integer followDayOfWeek;
    private LocalDate followDate;
    private String name;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
