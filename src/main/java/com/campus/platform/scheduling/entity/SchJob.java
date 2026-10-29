package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import com.campus.platform.scheduling.solver.model.SolverStatus;

/**
 * 排课任务（一次求解）：参数快照、状态、进度、指标与日志。
 */
@Getter
@Setter
@TableName("sch_job")
public class SchJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;
    private String name;
    private String mode;
    private Long baseVersionId;
    private SolverStatus status;
    private String paramsJson;
    private Boolean cancelRequested;
    private Integer progress;
    private String phase;
    private String solverStatus;
    private Long objective;
    private Long bestBound;
    private String statsJson;
    private Long resultVersionId;
    private String errorCode;
    private String message;
    private String logText;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
