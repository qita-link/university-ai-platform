package com.campus.platform.scheduling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 教学班成员：fullClass=true 为整班参加；false 为选修抽取（studentCount 必填）。
 */
@Getter
@Setter
@TableName("sch_teaching_class_member")
public class SchTeachingClassMember {

    private Long teachingClassId;
    private Long orgUnitId;
    private Boolean fullClass;
    private Integer studentCount;
    private LocalDateTime createdAt;
}
