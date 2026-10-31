package com.campus.platform.grade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 课程成绩（course_grade）：一门课程一名学生一条记录。
 * <p>
 * 教师录入为 {@link GradeStatus#DRAFT}，发布后学生可见并计入绩点。
 * 绩点与等级在保存时按 {@code grade_rule} 换算并留存在行上，便于历史追溯（规则调整不影响已发布成绩）。
 */
@Getter
@Setter
@TableName("course_grade")
public class CourseGrade extends AuditableEntity {

    private Long courseId;
    private Long studentId;
    private BigDecimal score;
    private BigDecimal gradePoint;
    private String gradeLevel;
    private GradeStatus status;
    private String remark;
    private Long gradedBy;
    private LocalDateTime publishedAt;

    @Version
    private Integer version;
}
