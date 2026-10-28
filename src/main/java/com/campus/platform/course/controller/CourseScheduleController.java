package com.campus.platform.course.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.course.dto.MyScheduleItem;
import com.campus.platform.course.dto.ScheduleRequest;
import com.campus.platform.course.dto.ScheduleView;
import com.campus.platform.course.service.CourseScheduleService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "排课与课表", description = "课程排课维护与本人课表")
@RestController
@RequestMapping("/api/v1")
public class CourseScheduleController {

    private final CourseScheduleService scheduleService;

    public CourseScheduleController(CourseScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Operation(summary = "新增排课", description = "主讲教师或管理员；校验本课程与授课教师的时间冲突")
    @PreAuthorize("hasAuthority('course:manage_own') or hasAuthority('course:manage_all')")
    @PostMapping("/courses/{courseId}/schedules")
    public ApiResponse<List<ScheduleView>> add(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable long courseId,
                                               @Valid @RequestBody ScheduleRequest request) {
        return ApiResponse.ok(scheduleService.add(user, courseId, request));
    }

    @Operation(summary = "修改排课", description = "主讲教师或管理员；同样校验时间冲突")
    @PreAuthorize("hasAuthority('course:manage_own') or hasAuthority('course:manage_all')")
    @PutMapping("/courses/{courseId}/schedules/{scheduleId}")
    public ApiResponse<List<ScheduleView>> update(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable long courseId,
                                                  @PathVariable long scheduleId,
                                                  @Valid @RequestBody ScheduleRequest request) {
        return ApiResponse.ok(scheduleService.update(user, courseId, scheduleId, request));
    }

    @Operation(summary = "删除排课", description = "主讲教师或管理员")
    @PreAuthorize("hasAuthority('course:manage_own') or hasAuthority('course:manage_all')")
    @DeleteMapping("/courses/{courseId}/schedules/{scheduleId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,
                                    @PathVariable long courseId, @PathVariable long scheduleId) {
        scheduleService.delete(user, courseId, scheduleId);
        return ApiResponse.ok();
    }

    @Operation(summary = "我的课表", description = "学生返回已选课程、教师返回任教课程；semesterId 为空时返回全部学期")
    @PreAuthorize("hasAuthority('course:read')")
    @GetMapping("/schedules/me")
    public ApiResponse<List<MyScheduleItem>> mySchedule(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @RequestParam(required = false) Long semesterId) {
        return ApiResponse.ok(scheduleService.mySchedule(user, semesterId));
    }
}
