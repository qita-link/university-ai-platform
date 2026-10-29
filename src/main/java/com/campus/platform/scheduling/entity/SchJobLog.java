package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 排课任务日志（按时间追加）。
 */
@Getter
@Setter
@TableName("sch_job_log")
public class SchJobLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;
    private String level;
    private String message;
    private LocalDateTime createdAt;
}
