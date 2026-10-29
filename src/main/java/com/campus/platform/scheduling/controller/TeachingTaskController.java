package com.campus.platform.scheduling.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.scheduling.dto.TaskDtos;
import com.campus.platform.scheduling.service.TeachingTaskService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.nio.charset.StandardCharsets;

/**
 * 教学任务：CRUD、批量导入导出、预检。
 * <p>
 * 与“排课任务（一次求解）”不同，教学任务是学期内“某课程由某教师面向某教学班开设”的业务对象，
 * 因此路径使用 /teaching-tasks，求解任务使用 /jobs，避免语义混淆。
 */
@Tag(name = "排课-教学任务", description = "教学任务维护、CSV 批量导入导出、排课数据预检")
@RestController
@RequestMapping("/api/v1/scheduling/teaching-tasks")
public class TeachingTaskController {

    private final TeachingTaskService taskService;

    public TeachingTaskController(TeachingTaskService taskService) {
        this.taskService = taskService;
    }

    @Operation(summary = "教学任务列表", description = "支持按学期、学院、课程、教学班、教师、状态与关键字过滤")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping
    public ApiResponse<PageResult<TaskDtos.TaskView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @Valid @ModelAttribute TaskDtos.TaskQuery query) {
        return ApiResponse.ok(taskService.list(user, query));
    }

    @Operation(summary = "教学任务详情")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/{id}")
    public ApiResponse<TaskDtos.TaskView> get(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable long id) {
        return ApiResponse.ok(taskService.get(user, id));
    }

    @Operation(summary = "新建教学任务", description = "totalHours 为空时按 每周次数×单次节数 估算，预检会校验与周次的一致性")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping
    public ApiResponse<TaskDtos.TaskView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @Valid @RequestBody TaskDtos.TaskRequest req) {
        return ApiResponse.ok(taskService.create(user, req));
    }

    @Operation(summary = "修改教学任务", description = "提交 version 时做乐观锁校验")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PutMapping("/{id}")
    public ApiResponse<TaskDtos.TaskView> update(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @PathVariable long id,
                                                 @Valid @RequestBody TaskDtos.TaskRequest req) {
        return ApiResponse.ok(taskService.update(user, id, req));
    }

    @Operation(summary = "删除教学任务", description = "已出现在课表版本中的任务不能删除，请改为 DISABLED")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        taskService.delete(user, id);
        return ApiResponse.ok();
    }

    @Operation(summary = "批量导入教学任务",
            description = "CSV：code,courseCode,teachingClassCode,teacherUsernames,startWeek,endWeek,weekParity,"
                    + "sessionsPerWeek,periodsPerSession,totalHours,roomRequired,note（教师用 | 分隔）")
    @PreAuthorize("hasAuthority('schedule:manage')")
    @PostMapping(value = "/import", consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public ApiResponse<TaskDtos.ImportResult> importCsv(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @RequestParam long semesterId,
                                                        @RequestBody String csv) {
        return ApiResponse.ok(taskService.importCsv(user, semesterId, csv));
    }

    @Operation(summary = "导出教学任务 CSV", description = "UTF-8 BOM，Excel 可直接打开")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user,
                                         @RequestParam long semesterId) {
        byte[] body = taskService.exportCsvBytes(user, semesterId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"teaching-tasks-" + semesterId + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @Operation(summary = "预检当前学期的排课输入数据")
    @PreAuthorize("hasAuthority('schedule:read')")
    @GetMapping("/precheck")
    public ApiResponse<TaskDtos.PrecheckView> precheck(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @RequestParam long semesterId,
                                                       @RequestParam(required = false) Long orgUnitId) {
        return ApiResponse.ok(taskService.precheck(user, semesterId, orgUnitId, null));
    }
}
