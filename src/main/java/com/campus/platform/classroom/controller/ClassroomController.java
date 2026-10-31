package com.campus.platform.classroom.controller;

import com.campus.platform.classroom.dto.FreeSlotView;
import com.campus.platform.classroom.dto.FreeWeekView;
import com.campus.platform.classroom.service.ClassroomService;
import com.campus.platform.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "空教室", description = "查询当前及未来一周的空闲教室")
@RestController
@RequestMapping("/api/v1/classrooms")
public class ClassroomController {

    private final ClassroomService classroomService;

    public ClassroomController(ClassroomService classroomService) {
        this.classroomService = classroomService;
    }

    @Operation(summary = "某天某节次的空教室",
            description = "不传日期取今天；不传节次取当前节次。占用来源为已发布排课与课程排课的并集")
    @PreAuthorize("hasAuthority('classroom:read')")
    @GetMapping("/free")
    public ApiResponse<FreeSlotView> free(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer startPeriod,
            @RequestParam(required = false) Integer endPeriod,
            @RequestParam(required = false) Integer minCapacity,
            @RequestParam(required = false) String building,
            @RequestParam(required = false) String roomType) {
        return ApiResponse.ok(classroomService.free(date, startPeriod, endPeriod,
                minCapacity, building, roomType));
    }

    @Operation(summary = "一周空教室概览", description = "默认从今天起 7 天，返回每天同一时段的空闲教室数量")
    @PreAuthorize("hasAuthority('classroom:read')")
    @GetMapping("/week")
    public ApiResponse<FreeWeekView> week(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) Integer startPeriod,
            @RequestParam(required = false) Integer endPeriod,
            @RequestParam(required = false) Integer minCapacity,
            @RequestParam(required = false) String building,
            @RequestParam(required = false) String roomType) {
        return ApiResponse.ok(classroomService.week(from, startPeriod, endPeriod,
                minCapacity, building, roomType));
    }
}
