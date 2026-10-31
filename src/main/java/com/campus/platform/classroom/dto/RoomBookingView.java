package com.campus.platform.classroom.dto;

import com.campus.platform.classroom.entity.RoomBookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "教室借用申请")
public record RoomBookingView(
        Long id,
        Long roomId,
        String roomCode,
        String roomName,
        String building,
        Long applicantId,
        String applicantName,
        LocalDate bookingDate,
        int startPeriod,
        int endPeriod,
        String purpose,
        Integer attendees,
        String contact,
        RoomBookingStatus status,
        String reviewerName,
        String reviewComment,
        LocalDateTime reviewedAt,
        boolean canCancel,
        boolean canReview,
        LocalDateTime createdAt) {
}
