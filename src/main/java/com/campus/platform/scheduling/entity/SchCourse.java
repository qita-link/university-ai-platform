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
 * 课程库（课程基本信息，与学期无关）。
 */
@Getter
@Setter
@TableName("sch_course")
public class SchCourse {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;
    private String courseType;
    private BigDecimal credit;
    private Integer totalHours;
    private Long orgUnitId;
    private String defaultRoomType;
    private String requiredFeatures;
    private Integer priority;
    private String status;
    private String description;
    @Version
    private Integer version;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
