package com.campus.platform.scheduling.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.scheduling.dto.CatalogDtos;
import com.campus.platform.scheduling.service.CourseCatalogService;
import com.campus.platform.scheduling.service.ResourceCatalogService;
import com.campus.platform.scheduling.service.RuleService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 排课基础数据：校区、教室、教师档案、行政班档案、课程库、教学班、时间规则。
 */
@Tag(name = "排课-基础数据", description = "校区与通行时间、教室与可用时间、教师/班级档案、课程库、教学班、时间规则")
@RestController
@RequestMapping("/api/v1/scheduling")
public class SchedulingCatalogController {

    private final ResourceCatalogService resourceService;
    private final CourseCatalogService courseService;
    private final RuleService ruleService;

    public SchedulingCatalogController(ResourceCatalogService resourceService,
                                       CourseCatalogService courseService, RuleService ruleService) {
        this.resourceService = resourceService;
        this.courseService = courseService;
        this.ruleService = ruleService;
    }

    // ------------------------------------------------------------------ 校区

    @Operation(summary = "校区列表")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/campuses")
    public ApiResponse<List<CatalogDtos.CampusView>> campuses(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(resourceService.listCampuses(user));
    }

    @Operation(summary = "新建校区")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping("/campuses")
    public ApiResponse<CatalogDtos.CampusView> createCampus(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @Valid @RequestBody CatalogDtos.CampusRequest req) {
        return ApiResponse.ok(resourceService.createCampus(user, req));
    }

    @Operation(summary = "修改校区")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/campuses/{campusId}")
    public ApiResponse<CatalogDtos.CampusView> updateCampus(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @PathVariable long campusId,
                                                            @Valid @RequestBody CatalogDtos.CampusRequest req) {
        return ApiResponse.ok(resourceService.updateCampus(user, campusId, req));
    }

    @Operation(summary = "校区通行时间")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/campuses/travel")
    public ApiResponse<List<CatalogDtos.TravelView>> travel(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(resourceService.listTravel(user));
    }

    @Operation(summary = "设置校区通行时间", description = "无序对，重复提交会覆盖")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/campuses/travel")
    public ApiResponse<List<CatalogDtos.TravelView>> putTravel(@AuthenticationPrincipal AuthenticatedUser user,
                                                               @Valid @RequestBody CatalogDtos.TravelRequest req) {
        return ApiResponse.ok(resourceService.putTravel(user, req));
    }

    // ------------------------------------------------------------------ 教室

    @Operation(summary = "教室列表", description = "教师可用（按教室查课需要选教室），学生不可用")
    @PreAuthorize("hasAnyAuthority('schedule:read','schedule:preference:self')")
    @GetMapping("/classrooms")
    public ApiResponse<PageResult<CatalogDtos.RoomView>> rooms(@AuthenticationPrincipal AuthenticatedUser user,
                                                               @Valid @ModelAttribute CatalogDtos.RoomQuery query) {
        return ApiResponse.ok(resourceService.listRooms(user, query));
    }

    @Operation(summary = "教室详情")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/classrooms/{classroomId}")
    public ApiResponse<CatalogDtos.RoomView> room(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable long classroomId) {
        return ApiResponse.ok(resourceService.getRoom(user, classroomId));
    }

    @Operation(summary = "新建教室")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping("/classrooms")
    public ApiResponse<CatalogDtos.RoomView> createRoom(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @Valid @RequestBody CatalogDtos.RoomRequest req) {
        return ApiResponse.ok(resourceService.createRoom(user, req));
    }

    @Operation(summary = "修改教室", description = "提交 version 时做乐观锁校验")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/classrooms/{classroomId}")
    public ApiResponse<CatalogDtos.RoomView> updateRoom(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @PathVariable long classroomId,
                                                        @Valid @RequestBody CatalogDtos.RoomRequest req) {
        return ApiResponse.ok(resourceService.updateRoom(user, classroomId, req));
    }

    @Operation(summary = "教室可用时间", description = "返回该教室在该学期的禁排/回避/偏好规则")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/classrooms/{classroomId}/availability")
    public ApiResponse<List<CatalogDtos.TimeRuleView>> roomAvailability(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long classroomId,
            @RequestParam long semesterId) {
        return ApiResponse.ok(resourceService.roomAvailability(user, classroomId, semesterId));
    }

    @Operation(summary = "设置教室可用时间", description = "按对象整体替换（FORBIDDEN 为维护/不开放，硬约束）")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/classrooms/{classroomId}/availability")
    public ApiResponse<List<CatalogDtos.TimeRuleView>> putRoomAvailability(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long classroomId,
            @RequestParam long semesterId,
            @Valid @RequestBody List<CatalogDtos.TimeRuleRequest> requests) {
        return ApiResponse.ok(resourceService.putRoomAvailability(user, classroomId, semesterId, requests));
    }

    // ------------------------------------------------------------------ 教师

    @Operation(summary = "教师列表（含排课档案与可授课程）")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/teachers")
    public ApiResponse<List<CatalogDtos.TeacherView>> teachers(@AuthenticationPrincipal AuthenticatedUser user,
                                                               @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(resourceService.listTeachers(user, keyword));
    }

    @Operation(summary = "修改教师排课档案", description = "上限为空表示不限制；courseIds 非空时整体替换可授课程")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/teachers/{teacherId}")
    public ApiResponse<CatalogDtos.TeacherView> putTeacher(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @PathVariable long teacherId,
                                                           @Valid @RequestBody CatalogDtos.TeacherProfileRequest req) {
        return ApiResponse.ok(resourceService.putTeacherProfile(user, teacherId, req));
    }

    @Operation(summary = "教师不可用/偏好时间")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/teachers/{teacherId}/availability")
    public ApiResponse<List<CatalogDtos.TimeRuleView>> teacherAvailability(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long teacherId,
            @RequestParam long semesterId) {
        return ApiResponse.ok(resourceService.teacherAvailability(user, teacherId, semesterId));
    }

    @Operation(summary = "设置教师可用时间", description = "FORBIDDEN 为硬约束；AVOID/PREFERRED 为软约束")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/teachers/{teacherId}/availability")
    public ApiResponse<List<CatalogDtos.TimeRuleView>> putTeacherAvailability(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long teacherId,
            @RequestParam long semesterId,
            @Valid @RequestBody List<CatalogDtos.TimeRuleRequest> requests) {
        return ApiResponse.ok(resourceService.putTeacherAvailability(user, teacherId, semesterId, requests));
    }

    @Operation(summary = "教师本人维护时间偏好", description = "需要 schedule:preference:self；只能改自己的 AVOID/PREFERRED")
    @PreAuthorize("hasAuthority('schedule:preference:self')")
    @PutMapping("/teachers/me/preferences")
    public ApiResponse<List<CatalogDtos.TimeRuleView>> myPreferences(
            @AuthenticationPrincipal AuthenticatedUser user, @RequestParam long semesterId,
            @Valid @RequestBody List<CatalogDtos.TimeRuleRequest> requests) {
        return ApiResponse.ok(ruleService.replaceOwnPreferences(user, semesterId, requests));
    }

    @Operation(summary = "教师工作量统计", description = "按已发布版本统计每周课次与节数")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/teachers/{teacherId}/workload")
    public ApiResponse<CatalogDtos.TeacherWorkloadView> workload(@AuthenticationPrincipal AuthenticatedUser user,
                                                                 @PathVariable long teacherId) {
        return ApiResponse.ok(resourceService.workload(user, teacherId));
    }

    // ------------------------------------------------------------------ 行政班

    @Operation(summary = "行政班列表（含排课档案）")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/classes")
    public ApiResponse<List<CatalogDtos.ClassView>> classes(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(resourceService.listClasses(user, keyword));
    }

    @Operation(summary = "修改行政班排课档案")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/classes/{classId}")
    public ApiResponse<CatalogDtos.ClassView> putClass(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @PathVariable long classId,
                                                       @Valid @RequestBody CatalogDtos.ClassProfileRequest req) {
        return ApiResponse.ok(resourceService.putClassProfile(user, classId, req));
    }

    @Operation(summary = "行政班禁排/偏好时间")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/classes/{classId}/availability")
    public ApiResponse<List<CatalogDtos.TimeRuleView>> classAvailability(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long classId,
            @RequestParam long semesterId) {
        return ApiResponse.ok(resourceService.classAvailability(user, classId, semesterId));
    }

    @Operation(summary = "设置行政班禁排/偏好时间")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/classes/{classId}/availability")
    public ApiResponse<List<CatalogDtos.TimeRuleView>> putClassAvailability(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long classId,
            @RequestParam long semesterId,
            @Valid @RequestBody List<CatalogDtos.TimeRuleRequest> requests) {
        return ApiResponse.ok(resourceService.putClassAvailability(user, classId, semesterId, requests));
    }

    // ------------------------------------------------------------------ 课程库

    @Operation(summary = "课程库列表")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/courses")
    public ApiResponse<List<CatalogDtos.CourseView>> courses(@AuthenticationPrincipal AuthenticatedUser user,
                                                             @RequestParam(required = false) String keyword,
                                                             @RequestParam(required = false) String status) {
        return ApiResponse.ok(courseService.listCourses(user, keyword, status));
    }

    @Operation(summary = "课程详情")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/courses/{courseId}")
    public ApiResponse<CatalogDtos.CourseView> course(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @PathVariable long courseId) {
        return ApiResponse.ok(courseService.getCourse(user, courseId));
    }

    @Operation(summary = "新建课程")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping("/courses")
    public ApiResponse<CatalogDtos.CourseView> createCourse(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @Valid @RequestBody CatalogDtos.CourseRequest req) {
        return ApiResponse.ok(courseService.createCourse(user, req));
    }

    @Operation(summary = "修改课程")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/courses/{courseId}")
    public ApiResponse<CatalogDtos.CourseView> updateCourse(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @PathVariable long courseId,
                                                            @Valid @RequestBody CatalogDtos.CourseRequest req) {
        return ApiResponse.ok(courseService.updateCourse(user, courseId, req));
    }

    @Operation(summary = "停用课程", description = "不物理删除，避免破坏历史教学任务")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @DeleteMapping("/courses/{courseId}")
    public ApiResponse<Void> disableCourse(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable long courseId) {
        courseService.disableCourse(user, courseId);
        return ApiResponse.ok();
    }

    // ------------------------------------------------------------------ 教学班

    @Operation(summary = "教学班列表")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/teaching-classes")
    public ApiResponse<List<CatalogDtos.TeachingClassView>> teachingClasses(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(courseService.listTeachingClasses(user, semesterId, keyword));
    }

    @Operation(summary = "教学班详情")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/teaching-classes/{id}")
    public ApiResponse<CatalogDtos.TeachingClassView> teachingClass(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(courseService.getTeachingClass(user, id));
    }

    @Operation(summary = "新建教学班", description = "fullClass=true 整班参加；false 为选修抽取（studentCount 必填）")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping("/teaching-classes")
    public ApiResponse<CatalogDtos.TeachingClassView> createTeachingClass(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CatalogDtos.TeachingClassRequest req) {
        return ApiResponse.ok(courseService.createTeachingClass(user, req));
    }

    @Operation(summary = "修改教学班")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/teaching-classes/{id}")
    public ApiResponse<CatalogDtos.TeachingClassView> updateTeachingClass(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
            @Valid @RequestBody CatalogDtos.TeachingClassRequest req) {
        return ApiResponse.ok(courseService.updateTeachingClass(user, id, req));
    }

    // ------------------------------------------------------------------ 时间规则

    @Operation(summary = "时间规则列表")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/rules")
    public ApiResponse<List<CatalogDtos.TimeRuleView>> rules(@AuthenticationPrincipal AuthenticatedUser user,
                                                             @RequestParam(required = false) Long semesterId,
                                                             @RequestParam(required = false) String targetType,
                                                             @RequestParam(required = false) Long targetId) {
        return ApiResponse.ok(ruleService.list(user, semesterId, targetType, targetId));
    }

    @Operation(summary = "规则模板", description = "午休禁排、晚间不排课、周五下午回避、减少早八等")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/rules/templates")
    public ApiResponse<List<Map<String, Object>>> templates(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(ruleService.templates(user));
    }

    @Operation(summary = "新建时间规则")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping("/rules")
    public ApiResponse<CatalogDtos.TimeRuleView> createRule(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @Valid @RequestBody CatalogDtos.TimeRuleRequest req) {
        return ApiResponse.ok(ruleService.create(user, req));
    }

    @Operation(summary = "修改时间规则")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/rules/{id}")
    public ApiResponse<CatalogDtos.TimeRuleView> updateRule(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @PathVariable long id,
                                                            @Valid @RequestBody CatalogDtos.TimeRuleRequest req) {
        return ApiResponse.ok(ruleService.update(user, id, req));
    }

    @Operation(summary = "删除时间规则")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        ruleService.delete(user, id);
        return ApiResponse.ok();
    }
}
