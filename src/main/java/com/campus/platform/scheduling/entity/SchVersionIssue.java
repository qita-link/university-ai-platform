package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 排课版本问题清单：未安排原因、硬约束违反、预检警告。
 */
@Getter
@Setter
@TableName("sch_version_issue")
public class SchVersionIssue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long versionId;
    private String severity;
    private String code;
    private Long taskId;
    private Long assignmentId;
    private String message;
    private LocalDateTime createdAt;
}
