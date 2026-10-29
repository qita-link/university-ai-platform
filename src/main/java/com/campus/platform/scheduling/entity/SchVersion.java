package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 排课版本：可调整、比较、发布的完整课表；每学期最多一个 PUBLISHED。
 */
@Getter
@Setter
@TableName("sch_version")
public class SchVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;
    private Integer versionNo;
    private String name;
    private String source;
    private Long jobId;
    private Long baseVersionId;
    private VersionStatus status;
    private Integer totalSessions;
    private Integer placedSessions;
    private Integer hardViolations;
    private BigDecimal score;
    private Long softPenalty;
    private String evaluationJson;
    private String note;
    private LocalDateTime publishedAt;
    private Long publishedBy;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
