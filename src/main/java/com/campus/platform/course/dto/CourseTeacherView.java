package com.campus.platform.course.dto;

import com.campus.platform.course.entity.TeacherRole;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "授课教师")
public record CourseTeacherView(
        Long teacherId,
        String username,
        String displayName,
        TeacherRole teacherRole) {
}
