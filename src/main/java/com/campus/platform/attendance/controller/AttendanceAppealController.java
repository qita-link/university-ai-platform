package com.campus.platform.attendance.controller;

import com.campus.platform.attendance.dto.AppealDecisionRequest;
import com.campus.platform.attendance.dto.AppealSubmitRequest;
import com.campus.platform.attendance.dto.AppealView;
import com.campus.platform.attendance.dto.MyAttendanceView;
import com.campus.platform.attendance.entity.AppealStatus;
import com.campus.platform.attendance.entity.AttendanceStatus;
import com.campus.platform.attendance.service.AttendanceAppealService;
import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "考勤申诉", description = "学生对旷课/迟到提出申诉，教师、导员与管理员复核")
@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceAppealController {

    private final AttendanceAppealService appealService;

    public AttendanceAppealController(AttendanceAppealService appealService) {
        this.appealService = appealService;
    }

    @Operation(summary = "我的考勤明细", description = "返回本人考勤，并标记哪些可以申诉")
    @PreAuthorize("hasAuthority('attendance:appeal')")
    @GetMapping("/me")
    public ApiResponse<List<MyAttendanceView>> myAttendance(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @RequestParam(required = false) AttendanceStatus status) {
        return ApiResponse.ok(appealService.myAttendance(user, status));
    }

    @Operation(summary = "提交考勤申诉", description = "仅本人、旷课/迟到、且在申诉期限内")
    @PreAuthorize("hasAuthority('attendance:appeal')")
    @PostMapping("/appeals")
    public ApiResponse<AppealView> submit(@AuthenticationPrincipal AuthenticatedUser user,
                                          @Valid @RequestBody AppealSubmitRequest request) {
        return ApiResponse.ok(appealService.submit(user, request));
    }

    @Operation(summary = "我的申诉记录")
    @PreAuthorize("hasAuthority('attendance:appeal')")
    @GetMapping("/appeals/me")
    public ApiResponse<List<AppealView>> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(appealService.mine(user));
    }

    @Operation(summary = "待我复核的申诉", description = "管理员全部，教师任教课程，导员所带班级")
    @PreAuthorize("hasAuthority('attendance:manage')")
    @GetMapping("/appeals")
    public ApiResponse<List<AppealView>> reviewList(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @RequestParam(required = false) AppealStatus status) {
        return ApiResponse.ok(appealService.reviewList(user, status));
    }

    @Operation(summary = "复核申诉", description = "通过时把考勤明细改为出勤/迟到/请假")
    @PreAuthorize("hasAuthority('attendance:manage')")
    @PostMapping("/appeals/{id}/decide")
    public ApiResponse<AppealView> decide(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                          @Valid @RequestBody AppealDecisionRequest request) {
        return ApiResponse.ok(appealService.decide(user, id, request));
    }
}
