package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 课时安排：教学任务第 k 次课（每周重复）在某版本中的星期/起始节/教室。
 */
@Getter
@Setter
@TableName("sch_assignment")
public class SchAssignment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long versionId;
    private Long taskId;
    private Integer sessionIndex;
    private Integer dayOfWeek;
    private Integer startPeriod;
    private Integer endPeriod;
    private Long roomId;
    private Boolean locked;
    private String origin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
