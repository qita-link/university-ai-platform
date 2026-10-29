package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 特殊教学周：teachingAllowed=false 的周（考试周/实践周/假期周）不排理论课。
 */
@Getter
@Setter
@TableName("sch_calendar_week")
public class SchCalendarWeek {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;
    private Integer weekNo;
    private String weekType;
    private Boolean teachingAllowed;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
