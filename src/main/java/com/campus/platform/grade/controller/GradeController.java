package com.campus.platform.grade.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.grade.dto.GradeBookView;
import com.campus.platform.grade.dto.GradeEntryRequest;
import com.campus.platform.grade.dto.GradeRuleView;
import com.campus.platform.grade.dto.TranscriptView;
import com.campus.platform.grade.service.GradeService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "成绩与绩点", description = "学生成绩单，教师成绩录入与发布")
@RestController
@RequestMapping("/api/v1/grades")
public class GradeController {

    private final GradeService gradeService;

    public GradeController(GradeService gradeService) {
        this.gradeService = gradeService;
    }

    @Operation(summary = "我的成绩单", description = "只返回已发布成绩；含总绩点、加权平均分与按学期分组")
    @PreAuthorize("hasAuthority('grade:read')")
    @GetMapping("/me")
    public ApiResponse<TranscriptView> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(gradeService.transcript(user));
    }

    @Operation(summary = "绩点换算规则")
    @PreAuthorize("hasAuthority('grade:read') or hasAuthority('grade:manage') or hasAuthority('grade:manage_all')")
    @GetMapping("/rules")
    public ApiResponse<List<GradeRuleView>> rules() {
        return ApiResponse.ok(gradeService.rules());
    }

    @Operation(summary = "课程成绩册", description = "任课教师或管理员")
    @PreAuthorize("hasAuthority('grade:manage') or hasAuthority('grade:manage_all')")
    @GetMapping("/course/{courseId}")
    public ApiResponse<GradeBookView> gradeBook(@AuthenticationPrincipal AuthenticatedUser user,
                                                @PathVariable long courseId) {
        return ApiResponse.ok(gradeService.gradeBook(user, courseId));
    }

    @Operation(summary = "保存成绩（草稿）", description = "任课教师或管理员；已发布成绩仅管理员可改")
    @PreAuthorize("hasAuthority('grade:manage') or hasAuthority('grade:manage_all')")
    @PutMapping("/course/{courseId}")
    public ApiResponse<GradeBookView> save(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable long courseId,
                                           @Valid @RequestBody GradeEntryRequest request) {
        return ApiResponse.ok(gradeService.save(user, courseId, request.items()));
    }

    @Operation(summary = "发布成绩", description = "要求全部选课学生都已录入分数")
    @PreAuthorize("hasAuthority('grade:manage') or hasAuthority('grade:manage_all')")
    @PostMapping("/course/{courseId}/publish")
    public ApiResponse<GradeBookView> publish(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable long courseId) {
        return ApiResponse.ok(gradeService.publish(user, courseId));
    }
}
