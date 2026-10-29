package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 教学任务：某学期某课程由某些教师面向某教学班开设。
 */
@Getter
@Setter
@TableName("sch_task")
public class SchTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;
    private String code;
    private Long courseId;
    private Long teachingClassId;
    private Long orgUnitId;
    private Integer startWeek;
    private Integer endWeek;
    private String weekParity;
    private Integer sessionsPerWeek;
    private Integer periodsPerSession;
    private Integer totalHours;
    private Boolean roomRequired;
    private String roomType;
    private String requiredFeatures;
    private Integer priority;
    private Boolean allowSameDay;
    private Boolean uniformStart;
    private Long linkedCourseId;
    private String status;
    private String note;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
