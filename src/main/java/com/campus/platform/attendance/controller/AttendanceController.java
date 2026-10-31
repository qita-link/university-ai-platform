package com.campus.platform.attendance.controller;

import com.campus.platform.attendance.dto.AttendanceCreateRequest;
import com.campus.platform.attendance.dto.AttendanceSaveRequest;
import com.campus.platform.attendance.dto.AttendanceSessionDetailView;
import com.campus.platform.attendance.dto.AttendanceSessionView;
import com.campus.platform.attendance.dto.ClassAttendanceStatsView;
import com.campus.platform.attendance.dto.ClassOptionView;
import com.campus.platform.attendance.dto.ClassTimetableView;
import com.campus.platform.attendance.service.AttendanceService;
import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "班级考勤", description = "按班级课表登记与查看考勤、旷课统计")
@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @Operation(summary = "行政班列表", description = "考勤与请假查询用的班级选项")
    @PreAuthorize("hasAuthority('attendance:read') or hasAuthority('attendance:manage')")
    @GetMapping("/classes")
    public ApiResponse<List<ClassOptionView>> classes(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(attendanceService.classes(user));
    }

    @Operation(summary = "班级课表与旷课", description = "按班查看每门课的节次与已登记/旷课人次")
    @PreAuthorize("hasAuthority('attendance:read') or hasAuthority('attendance:manage')")
    @GetMapping("/classes/{classId}/timetable")
    public ApiResponse<ClassTimetableView> timetable(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @PathVariable long classId,
                                                     @RequestParam(required = false) Long semesterId) {
        return ApiResponse.ok(attendanceService.classTimetable(user, classId, semesterId));
    }

    @Operation(summary = "班级考勤统计", description = "每名学生的出勤、旷课与旷课率")
    @PreAuthorize("hasAuthority('attendance:read') or hasAuthority('attendance:manage')")
    @GetMapping("/classes/{classId}/stats")
    public ApiResponse<ClassAttendanceStatsView> stats(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @PathVariable long classId,
                                                       @RequestParam(required = false) Long semesterId,
                                                       @RequestParam(required = false) Long courseId) {
        return ApiResponse.ok(attendanceService.classStats(user, classId, semesterId, courseId));
    }

    @Operation(summary = "考勤场次列表")
    @PreAuthorize("hasAuthority('attendance:read') or hasAuthority('attendance:manage')")
    @GetMapping("/sessions")
    public ApiResponse<List<AttendanceSessionView>> sessions(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam long classId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(attendanceService.sessions(user, classId, courseId, from, to));
    }

    @Operation(summary = "新建考勤场次", description = "任课教师/导员/管理员；自动把当天已通过的请假标记为请假")
    @PreAuthorize("hasAuthority('attendance:manage')")
    @PostMapping("/sessions")
    public ApiResponse<AttendanceSessionDetailView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @Valid @RequestBody AttendanceCreateRequest request) {
        return ApiResponse.ok(attendanceService.create(user, request));
    }

    @Operation(summary = "考勤场次详情")
    @PreAuthorize("hasAuthority('attendance:read') or hasAuthority('attendance:manage')")
    @GetMapping("/sessions/{id}")
    public ApiResponse<AttendanceSessionDetailView> detail(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @PathVariable long id) {
        return ApiResponse.ok(attendanceService.detail(user, id));
    }

    @Operation(summary = "保存考勤明细")
    @PreAuthorize("hasAuthority('attendance:manage')")
    @PutMapping("/sessions/{id}")
    public ApiResponse<AttendanceSessionDetailView> save(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @PathVariable long id,
                                                         @Valid @RequestBody AttendanceSaveRequest request) {
        return ApiResponse.ok(attendanceService.save(user, id, request.items()));
    }

    @Operation(summary = "同步考勤名单", description = "把新加入班级的学生补进该场次；已通过请假自动标为请假")
    @PreAuthorize("hasAuthority('attendance:manage')")
    @PostMapping("/sessions/{id}/sync-roster")
    public ApiResponse<AttendanceSessionDetailView> syncRoster(@AuthenticationPrincipal AuthenticatedUser user,
                                                               @PathVariable long id) {
        return ApiResponse.ok(attendanceService.syncRoster(user, id));
    }

    @Operation(summary = "提交考勤", description = "提交后教师不可再修改，管理员可改")
    @PreAuthorize("hasAuthority('attendance:manage')")
    @PostMapping("/sessions/{id}/finalize")
    public ApiResponse<AttendanceSessionDetailView> finalizeSession(@AuthenticationPrincipal AuthenticatedUser user,
                                                                    @PathVariable long id) {
        return ApiResponse.ok(attendanceService.finalizeSession(user, id));
    }
}
