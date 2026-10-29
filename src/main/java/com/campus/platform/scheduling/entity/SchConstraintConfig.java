package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 软约束权重配置（按学期覆盖默认值，硬约束不可关闭）。
 */
@Getter
@Setter
@TableName("sch_constraint_config")
public class SchConstraintConfig {

    private Long semesterId;
    private String code;
    private Boolean enabled;
    private Integer weight;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
