package com.campus.platform.course.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageQuery;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.course.dto.AvailableCourseView;
import com.campus.platform.course.dto.CourseCreateRequest;
import com.campus.platform.course.dto.CourseDetailView;
import com.campus.platform.course.dto.CourseQuery;
import com.campus.platform.course.dto.CourseStatusRequest;
import com.campus.platform.course.dto.CourseSummaryView;
import com.campus.platform.course.dto.CourseTeacherRequest;
import com.campus.platform.course.dto.CourseTeacherView;
import com.campus.platform.course.dto.CourseUpdateRequest;
import com.campus.platform.course.dto.RosterItem;
import com.campus.platform.course.service.CourseScheduleService;
import com.campus.platform.course.service.CourseSelectionService;
import com.campus.platform.course.service.CourseService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "课程", description = "课程查询、创建、状态机、授课教师、可选课程与名单")
@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CourseService courseService;
    private final CourseScheduleService scheduleService;
    private final CourseSelectionService selectionService;

    public CourseController(CourseService courseService, CourseScheduleService scheduleService,
                            CourseSelectionService selectionService) {
        this.courseService = courseService;
        this.scheduleService = scheduleService;
        this.selectionService = selectionService;
    }

    @Operation(summary = "课程列表", description = "默认排除已停开课程；status=CANCELLED 仅管理员可用")
    @PreAuthorize("hasAuthority('course:read')")
    @GetMapping
    public ApiResponse<PageResult<CourseSummaryView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @Valid @ParameterObject CourseQuery query) {
        return ApiResponse.ok(courseService.list(user, query));
    }

    @Operation(summary = "可选课程", description = "本学期 OPEN 课程，逐条返回 selectable 与 blockedReason")
    @PreAuthorize("hasAuthority('course:read')")
    @GetMapping("/available")
    public ApiResponse<List<AvailableCourseView>> available(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @RequestParam long semesterId,
                                                            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(courseService.listAvailable(user, semesterId, keyword));
    }

    @Operation(summary = "课程详情", description = "含授课教师、排课与本人选课状态；已停开课程对普通用户 404")
    @PreAuthorize("hasAuthority('course:read')")
    @GetMapping("/{id}")
    public ApiResponse<CourseDetailView> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(courseService.get(user, id));
    }

    @Operation(summary = "创建课程", description = "需要 course:manage_own 或 course:manage_all；创建人自动成为主讲")
    @PreAuthorize("hasAuthority('course:manage_own') or hasAuthority('course:manage_all')")
    @PostMapping
    public ApiResponse<CourseDetailView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                @Valid @RequestBody CourseCreateRequest request) {
        return ApiResponse.ok(courseService.create(user, request));
    }

    @Operation(summary = "修改课程", description = "主讲教师或管理员；必须回传 version")
    @PreAuthorize("hasAuthority('course:manage_own') or hasAuthority('course:manage_all')")
    @PutMapping("/{id}")
    public ApiResponse<CourseDetailView> update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                                @Valid @RequestBody CourseUpdateRequest request) {
        return ApiResponse.ok(courseService.update(user, id, request));
    }

    @Operation(summary = "课程状态变更", description = "开放/关闭/停开；开放前必须已排课，停开为终态")
    @PreAuthorize("hasAuthority('course:manage_own') or hasAuthority('course:manage_all')")
    @PatchMapping("/{id}/status")
    public ApiResponse<CourseDetailView> changeStatus(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @PathVariable long id,
                                                      @Valid @RequestBody CourseStatusRequest request) {
        return ApiResponse.ok(courseService.changeStatus(user, id, request));
    }

    @Operation(summary = "授课教师列表")
    @PreAuthorize("hasAuthority('course:read')")
    @GetMapping("/{id}/teachers")
    public ApiResponse<List<CourseTeacherView>> teachers(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @PathVariable long id) {
        return ApiResponse.ok(courseService.get(user, id).teachers());
    }

    @Operation(summary = "添加/变更授课教师", description = "主讲教师或管理员；变更主讲需要 course:manage_all")
    @PreAuthorize("hasAuthority('course:manage_own') or hasAuthority('course:manage_all')")
    @PostMapping("/{id}/teachers")
    public ApiResponse<List<CourseTeacherView>> addTeacher(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @PathVariable long id,
                                                           @Valid @RequestBody CourseTeacherRequest request) {
        return ApiResponse.ok(courseService.addTeacher(user, id, request));
    }

    @Operation(summary = "移除授课教师", description = "主讲必须先改派，不能直接移除")
    @PreAuthorize("hasAuthority('course:manage_own') or hasAuthority('course:manage_all')")
    @DeleteMapping("/{id}/teachers/{teacherId}")
    public ApiResponse<List<CourseTeacherView>> removeTeacher(@AuthenticationPrincipal AuthenticatedUser user,
                                                              @PathVariable long id,
                                                              @PathVariable long teacherId) {
        return ApiResponse.ok(courseService.removeTeacher(user, id, teacherId));
    }

    @Operation(summary = "排课列表")
    @PreAuthorize("hasAuthority('course:read')")
    @GetMapping("/{id}/schedules")
    public ApiResponse<List<com.campus.platform.course.dto.ScheduleView>> schedules(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(scheduleService.list(user, id));
    }

    @Operation(summary = "课程选课名单", description = "任课教师或管理员；只返回有效选课")
    @PreAuthorize("hasAuthority('course:read')")
    @GetMapping("/{id}/selections")
    public ApiResponse<PageResult<RosterItem>> roster(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @PathVariable long id,
                                                      @Valid @ParameterObject PageQuery query) {
        return ApiResponse.ok(selectionService.roster(user, id, query));
    }
}
