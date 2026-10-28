package com.campus.platform.course.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 课程授课教师（course_teacher）。
 * <p>
 * 复合主键 (course_id, teacher_id)，不使用自增主键，因此不继承 BaseEntity；
 * 读写全部通过 {@code CourseTeacherMapper} 的显式 SQL 完成。
 */
@Getter
@Setter
public class CourseTeacher {

    private Long courseId;
    private Long teacherId;
    private TeacherRole teacherRole;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
