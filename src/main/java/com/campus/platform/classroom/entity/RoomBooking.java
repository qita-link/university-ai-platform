package com.campus.platform.classroom.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 教室借用申请（room_booking）：借用与「空教室」共用同一套占用口径，
 * 审批时会再次校验课表与已批准借用，避免并发把同一间教室批给两个人。
 */
@Getter
@Setter
@TableName("room_booking")
public class RoomBooking extends AuditableEntity {

    private Long roomId;
    private Long applicantId;
    private LocalDate bookingDate;
    private Integer startPeriod;
    private Integer endPeriod;
    private String purpose;
    private Integer attendees;
    private String contact;
    private RoomBookingStatus status;
    private Long reviewerId;
    private String reviewComment;
    private LocalDateTime reviewedAt;

    @Version
    private Integer version;
}
