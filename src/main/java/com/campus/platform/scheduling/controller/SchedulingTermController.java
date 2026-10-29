package com.campus.platform.scheduling.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.scheduling.dto.TaskDtos;
import com.campus.platform.scheduling.dto.TermDtos;
import com.campus.platform.scheduling.service.RuleService;
import com.campus.platform.scheduling.service.TeachingTaskService;
import com.campus.platform.scheduling.service.TermCalendarService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

import java.util.List;

/**
 * 学期排课配置、教学日历、约束权重与数据预检。
 */
@Tag(name = "排课-学期与日历", description = "学期排课参数、节次作息、特殊周/日期、软约束权重、排课预检")
@RestController
@RequestMapping("/api/v1/scheduling/terms")
public class SchedulingTermController {

    private final TermCalendarService termService;
    private final TeachingTaskService taskService;
    private final RuleService ruleService;

    public SchedulingTermController(TermCalendarService termService, TeachingTaskService taskService,
                                    RuleService ruleService) {
        this.termService = termService;
        this.taskService = taskService;
        this.ruleService = ruleService;
    }

    @Operation(summary = "学期列表（含排课配置）")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping
    public ApiResponse<List<TermDtos.TermView>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(termService.list(user));
    }

    @Operation(summary = "新建学期并建立排课配置",
            description = "复用 semester 表；同时生成默认节次作息（可用 PUT /periods 覆盖）")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping
    public ApiResponse<TermDtos.TermView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @Valid @RequestBody TermDtos.TermCreateRequest request) {
        return ApiResponse.ok(termService.create(user, request));
    }

    @Operation(summary = "学期详情")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/{semesterId}")
    public ApiResponse<TermDtos.TermView> get(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable long semesterId) {
        return ApiResponse.ok(termService.get(user, semesterId));
    }

    @Operation(summary = "修改排课配置", description = "教学周数、第 1 周周一（必须是周一）、教学日、每日节数")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/{semesterId}/config")
    public ApiResponse<TermDtos.ConfigView> putConfig(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @PathVariable long semesterId,
                                                      @Valid @RequestBody TermDtos.ConfigRequest request) {
        return ApiResponse.ok(termService.putConfig(user, semesterId, request));
    }

    @Operation(summary = "节次作息")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/{semesterId}/periods")
    public ApiResponse<List<TermDtos.PeriodView>> periods(@AuthenticationPrincipal AuthenticatedUser user,
                                                          @PathVariable long semesterId) {
        return ApiResponse.ok(termService.periods(user, semesterId));
    }

    @Operation(summary = "整体替换节次作息",
            description = "同一作息段（MORNING/AFTERNOON/EVENING）内编号连续才能连堂；开始时间不可重叠")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/{semesterId}/periods")
    public ApiResponse<List<TermDtos.PeriodView>> putPeriods(@AuthenticationPrincipal AuthenticatedUser user,
                                                             @PathVariable long semesterId,
                                                             @Valid @RequestBody TermDtos.PeriodRequest request) {
        return ApiResponse.ok(termService.putPeriods(user, semesterId, request));
    }

    @Operation(summary = "教学日历（特殊教学周 + 特殊日期）")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/{semesterId}/calendar")
    public ApiResponse<TermDtos.CalendarView> calendar(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @PathVariable long semesterId) {
        return ApiResponse.ok(termService.calendar(user, semesterId));
    }

    @Operation(summary = "整体替换教学日历",
            description = "HOLIDAY 节假日、SUSPENDED 临时停课、WORKDAY 调休上课（需 followDayOfWeek）、MAKEUP 补课（需 followDate）")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/{semesterId}/calendar")
    public ApiResponse<TermDtos.CalendarView> putCalendar(@AuthenticationPrincipal AuthenticatedUser user,
                                                          @PathVariable long semesterId,
                                                          @Valid @RequestBody TermDtos.CalendarRequest request) {
        return ApiResponse.ok(termService.putCalendar(user, semesterId, request));
    }

    @Operation(summary = "约束清单与权重", description = "硬约束不可关闭；软约束可关闭或调整权重")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/{semesterId}/constraints")
    public ApiResponse<List<TermDtos.ConstraintView>> constraints(@AuthenticationPrincipal AuthenticatedUser user,
                                                                  @PathVariable long semesterId) {
        return ApiResponse.ok(termService.constraints(user, semesterId));
    }

    @Operation(summary = "修改软约束权重", description = "权重会直接进入 CP-SAT 目标函数")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/{semesterId}/constraints")
    public ApiResponse<List<TermDtos.ConstraintView>> putConstraints(@AuthenticationPrincipal AuthenticatedUser user,
                                                                     @PathVariable long semesterId,
                                                                     @Valid @RequestBody
                                                                     TermDtos.ConstraintRequest request) {
        return ApiResponse.ok(termService.putConstraints(user, semesterId, request));
    }

    @Operation(summary = "排课数据预检",
            description = "检查学时一致性、候选为空、工作量超限、教室容量/类型等；ERROR 会阻止求解与发布")
    @PreAuthorize("hasAuthority('schedule:read')")
    @PostMapping("/{semesterId}/precheck")
    public ApiResponse<TaskDtos.PrecheckView> precheck(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @PathVariable long semesterId,
                                                       @RequestParam(required = false) Long orgUnitId) {
        return ApiResponse.ok(taskService.precheck(user, semesterId, orgUnitId, null));
    }

    @Operation(summary = "教师时间要求", description = "读取某教师在本学期允许来校的星期与半天；空表示不作限制")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/{semesterId}/teacher-availability/{teacherId}")
    public ApiResponse<TermDtos.TeacherAvailabilityView> teacherAvailability(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long semesterId,
            @PathVariable long teacherId) {
        return ApiResponse.ok(ruleService.teacherAvailability(user, semesterId, teacherId));
    }

    @Operation(summary = "设置教师时间要求",
            description = "只能管理员（schedule:manage）设置：外聘教师只能某天来、只能上午来等硬性要求，"
                    + "落成 AVAILABLE 时间规则，求解与复核都按硬约束处理")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/{semesterId}/teacher-availability/{teacherId}")
    public ApiResponse<TermDtos.TeacherAvailabilityView> putTeacherAvailability(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long semesterId,
            @PathVariable long teacherId,
            @RequestBody(required = false) TermDtos.TeacherAvailabilityRequest request) {
        return ApiResponse.ok(ruleService.putTeacherAvailability(user, semesterId, teacherId, request));
    }
}
