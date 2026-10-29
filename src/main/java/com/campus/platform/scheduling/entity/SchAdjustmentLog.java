package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 课表人工调整记录。
 */
@Getter
@Setter
@TableName("sch_adjustment_log")
public class SchAdjustmentLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long versionId;
    private Long assignmentId;
    private String action;
    private Integer fromDay;
    private Integer fromPeriod;
    private Long fromRoomId;
    private Integer toDay;
    private Integer toPeriod;
    private Long toRoomId;
    private String reason;
    private Long createdBy;
    private LocalDateTime createdAt;
}
