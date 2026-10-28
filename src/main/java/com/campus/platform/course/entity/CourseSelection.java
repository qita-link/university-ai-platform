package com.campus.platform.course.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 选课记录（course_selection）。
 * <p>
 * 不包含数据库生成列 {@code active_key}：该列只用于唯一约束，由数据库维护。
 */
@Getter
@Setter
@TableName("course_selection")
public class CourseSelection extends AuditableEntity {

    private Long courseId;
    private Long studentId;
    private SelectionStatus status;
    private LocalDateTime selectedAt;
    private LocalDateTime droppedAt;
}
