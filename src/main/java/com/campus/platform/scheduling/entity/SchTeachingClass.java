package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 教学班：实际参与某次教学安排的学生集合。
 */
@Getter
@Setter
@TableName("sch_teaching_class")
public class SchTeachingClass {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;
    private String code;
    private String name;
    private Integer studentCount;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
