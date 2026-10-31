package com.campus.platform.classroom.dto;

import com.campus.platform.classroom.entity.RoomBookingStatus;
import com.campus.platform.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@Schema(description = "教室借用查询")
public class RoomBookingQuery extends PageQuery {

    private RoomBookingStatus status;

    private Long roomId;

    private LocalDate from;

    private LocalDate to;
}
