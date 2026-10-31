package com.campus.platform.classroom.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "教室")
public record RoomView(
        Long id,
        String code,
        String name,
        String building,
        Integer floorNo,
        Integer capacity,
        String roomType,
        String features) {
}
