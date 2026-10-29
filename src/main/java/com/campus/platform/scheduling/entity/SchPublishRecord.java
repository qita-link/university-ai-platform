package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 课表发布/撤销发布/归档记录。
 */
@Getter
@Setter
@TableName("sch_publish_record")
public class SchPublishRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;
    private Long versionId;
    private String action;
    private Long operatorId;
    private String reason;
    private LocalDateTime createdAt;
}
