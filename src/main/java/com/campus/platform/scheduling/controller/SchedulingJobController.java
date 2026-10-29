package com.campus.platform.scheduling.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.scheduling.dto.JobDtos;
import com.campus.platform.scheduling.service.SchedulingJobService;
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

/**
 * 排课任务（一次求解）：创建、启动、状态、取消、重试、日志、诊断。
 * <p>
 * 启动接口立即返回任务 ID 与初始状态；客户端轮询 {@code /status} 获取进度。
 */
@Tag(name = "排课-求解任务", description = "创建/启动/取消/重试排课任务，查询进度、日志、诊断")
@RestController
@RequestMapping("/api/v1/scheduling/jobs")
public class SchedulingJobController {

    private final SchedulingJobService jobService;

    public SchedulingJobController(SchedulingJobService jobService) {
        this.jobService = jobService;
    }

    @Operation(summary = "创建排课任务", description = "不传 taskIds 表示学期内全部启用任务；mode=INCREMENTAL 需指定 baseVersionId")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping
    public ApiResponse<JobDtos.JobView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                               @Valid @RequestBody JobDtos.JobCreateRequest req) {
        return ApiResponse.ok(jobService.create(user, req));
    }

    @Operation(summary = "排课任务列表")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping
    public ApiResponse<PageResult<JobDtos.JobView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @RequestParam(required = false) Long semesterId,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(jobService.list(user, semesterId, status, page, size));
    }

    @Operation(summary = "排课任务详情")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/{jobId}")
    public ApiResponse<JobDtos.JobView> get(@AuthenticationPrincipal AuthenticatedUser user,
                                            @PathVariable long jobId) {
        return ApiResponse.ok(jobService.get(user, jobId));
    }

    @Operation(summary = "启动排课任务", description = "异步执行，立即返回；同一任务不能重复启动，同一学期同时只允许一个任务")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping("/{jobId}/run")
    public ApiResponse<JobDtos.JobStatusView> run(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable long jobId) {
        return ApiResponse.ok(jobService.run(user, jobId));
    }

    @Operation(summary = "取消排课任务", description = "排队中或执行中可取消；执行中会中断 CP-SAT 搜索")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping("/{jobId}/cancel")
    public ApiResponse<JobDtos.JobStatusView> cancel(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @PathVariable long jobId) {
        return ApiResponse.ok(jobService.cancel(user, jobId));
    }

    @Operation(summary = "重试排课任务", description = "复制原任务参数创建新任务（保留历史）")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping("/{jobId}/retry")
    public ApiResponse<JobDtos.JobView> retry(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable long jobId) {
        return ApiResponse.ok(jobService.retry(user, jobId));
    }

    @Operation(summary = "任务状态与进度")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/{jobId}/status")
    public ApiResponse<JobDtos.JobStatusView> status(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @PathVariable long jobId) {
        return ApiResponse.ok(jobService.status(jobId));
    }

    @Operation(summary = "任务日志")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/{jobId}/logs")
    public ApiResponse<List<JobDtos.JobLogView>> logs(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @PathVariable long jobId) {
        return ApiResponse.ok(jobService.logs(user, jobId));
    }

    @Operation(summary = "任务诊断", description = "预检问题、未安排原因与资源紧张度；无解时给出尽量具体的线索")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/{jobId}/diagnostics")
    public ApiResponse<JobDtos.DiagnosticsView> diagnostics(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @PathVariable long jobId) {
        return ApiResponse.ok(jobService.diagnostics(user, jobId));
    }
}
