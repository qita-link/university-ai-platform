package com.campus.platform.course.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.course.dto.SemesterRequest;
import com.campus.platform.course.dto.SemesterStatusRequest;
import com.campus.platform.course.dto.SemesterView;
import com.campus.platform.course.service.SemesterService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "学期", description = "学期管理与选课窗口")
@RestController
@RequestMapping("/api/v1/semesters")
public class SemesterController {

    private final SemesterService semesterService;

    public SemesterController(SemesterService semesterService) {
        this.semesterService = semesterService;
    }

    @Operation(summary = "学期列表", description = "登录且具备 course:read 即可读取，按开始日期倒序")
    @PreAuthorize("hasAuthority('course:read')")
    @GetMapping
    public ApiResponse<List<SemesterView>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(semesterService.list(user));
    }

    @Operation(summary = "当前学期", description = "今天落在起止日期内的 ACTIVE 学期；没有则返回最近一个 ACTIVE 学期")
    @PreAuthorize("hasAuthority('course:read')")
    @GetMapping("/current")
    public ApiResponse<SemesterView> current(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(semesterService.current(user));
    }

    @Operation(summary = "学期详情")
    @PreAuthorize("hasAuthority('course:read')")
    @GetMapping("/{id}")
    public ApiResponse<SemesterView> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(semesterService.get(user, id));
    }

    @Operation(summary = "新建学期", description = "需要 course:manage_all；新建为 DRAFT")
    @PreAuthorize("hasAuthority('course:manage_all')")
    @PostMapping
    public ApiResponse<SemesterView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                            @Valid @RequestBody SemesterRequest request) {
        return ApiResponse.ok(semesterService.create(user, request));
    }

    @Operation(summary = "修改学期", description = "需要 course:manage_all；已结束学期不可修改")
    @PreAuthorize("hasAuthority('course:manage_all')")
    @PutMapping("/{id}")
    public ApiResponse<SemesterView> update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                            @Valid @RequestBody SemesterRequest request) {
        return ApiResponse.ok(semesterService.update(user, id, request));
    }

    @Operation(summary = "学期状态变更", description = "DRAFT→ACTIVE/CLOSED，ACTIVE→CLOSED；CLOSED 为终态")
    @PreAuthorize("hasAuthority('course:manage_all')")
    @PatchMapping("/{id}/status")
    public ApiResponse<SemesterView> changeStatus(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable long id,
                                                  @Valid @RequestBody SemesterStatusRequest request) {
        return ApiResponse.ok(semesterService.changeStatus(user, id, request.status()));
    }
}
