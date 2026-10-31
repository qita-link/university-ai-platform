package com.campus.platform.course.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "班级导员")
public record ClassCounselorView(
        Long classId,
        String classCode,
        String className,
        List<Long> counselorIds,
        List<String> counselorNames) {
}
