package com.campus.platform.classroom.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 排课结果占用的行（查询映射用，不作为接口出入参）。
 */
@Getter
@Setter
public class RoomOccupancyRow {

    private Long roomId;
    private String roomCode;
    private String roomName;
    private Integer dayOfWeek;
    private Integer startPeriod;
    private Integer endPeriod;
    private Integer startWeek;
    private Integer endWeek;
    private String weekParity;
}
