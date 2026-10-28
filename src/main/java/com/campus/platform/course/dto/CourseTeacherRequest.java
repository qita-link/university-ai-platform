package com.campus.platform.course.dto;

import com.campus.platform.course.entity.TeacherRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "添加/变更授课教师")
public record CourseTeacherRequest(
        @Schema(description = "教师用户 ID") @NotNull @Positive Long teacherId,
        @Schema(description = "PRIMARY 主讲 / ASSISTANT 助教；变更主讲需要 course:manage_all")
        @NotNull TeacherRole teacherRole) {
}
