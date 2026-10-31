package com.campus.platform.classroom.controller;

import com.campus.platform.classroom.dto.RoomBookingCreateRequest;
import com.campus.platform.classroom.dto.RoomBookingDecisionRequest;
import com.campus.platform.classroom.dto.RoomBookingQuery;
import com.campus.platform.classroom.dto.RoomBookingView;
import com.campus.platform.classroom.service.RoomBookingService;
import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "教室借用", description = "查询空教室后直接申请借用，管理员审批")
@RestController
@RequestMapping("/api/v1/room-bookings")
public class RoomBookingController {

    private final RoomBookingService bookingService;

    public RoomBookingController(RoomBookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Operation(summary = "申请借用", description = "需要 room:book；会校验课表与已批准借用")
    @PreAuthorize("hasAuthority('room:book')")
    @PostMapping
    public ApiResponse<RoomBookingView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                               @Valid @RequestBody RoomBookingCreateRequest request) {
        return ApiResponse.ok(bookingService.create(user, request));
    }

    @Operation(summary = "我的借用")
    @PreAuthorize("hasAuthority('room:book')")
    @GetMapping("/me")
    public ApiResponse<PageResult<RoomBookingView>> mine(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @Valid @ParameterObject RoomBookingQuery query) {
        return ApiResponse.ok(bookingService.mine(user, query));
    }

    @Operation(summary = "待审批借用", description = "需要 room:approve；默认只看待审批")
    @PreAuthorize("hasAuthority('room:approve')")
    @GetMapping
    public ApiResponse<PageResult<RoomBookingView>> review(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @Valid @ParameterObject RoomBookingQuery query) {
        return ApiResponse.ok(bookingService.review(user, query));
    }

    @Operation(summary = "审批借用", description = "通过时会再次校验冲突")
    @PreAuthorize("hasAuthority('room:approve')")
    @PostMapping("/{id}/decide")
    public ApiResponse<RoomBookingView> decide(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                               @Valid @RequestBody RoomBookingDecisionRequest request) {
        return ApiResponse.ok(bookingService.decide(user, id, request));
    }

    @Operation(summary = "取消借用", description = "申请人本人，仅待审批状态")
    @PreAuthorize("hasAuthority('room:book')")
    @PostMapping("/{id}/cancel")
    public ApiResponse<RoomBookingView> cancel(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable long id) {
        return ApiResponse.ok(bookingService.cancel(user, id));
    }
}
