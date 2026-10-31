package com.campus.platform.evaluation.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.evaluation.dto.EvaluationCourseSummary;
import com.campus.platform.evaluation.dto.EvaluationOverview;
import com.campus.platform.evaluation.dto.EvaluationSubmitRequest;
import com.campus.platform.evaluation.dto.EvaluationTaskView;
import com.campus.platform.evaluation.service.EvaluationService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "学生评教", description = "学生给任课教师打分，教师查看聚合结果")
@RestController
@RequestMapping("/api/v1/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @Operation(summary = "我的评教任务", description = "本人已选课程 × 授课教师，含是否已评")
    @PreAuthorize("hasAuthority('evaluation:submit')")
    @GetMapping("/tasks")
    public ApiResponse<List<EvaluationTaskView>> tasks(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(evaluationService.tasks(user));
    }

    @Operation(summary = "提交评教", description = "每门课每位教师只能评一次")
    @PreAuthorize("hasAuthority('evaluation:submit')")
    @PostMapping
    public ApiResponse<EvaluationTaskView> submit(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @Valid @RequestBody EvaluationSubmitRequest request) {
        return ApiResponse.ok(evaluationService.submit(user, request));
    }

    @Operation(summary = "课程评教聚合", description = "任课教师看本人评分，管理员看全部")
    @PreAuthorize("hasAuthority('evaluation:read')")
    @GetMapping("/course/{courseId}")
    public ApiResponse<EvaluationCourseSummary> courseSummary(@AuthenticationPrincipal AuthenticatedUser user,
                                                              @PathVariable long courseId) {
        return ApiResponse.ok(evaluationService.courseSummary(user, courseId));
    }

    @Operation(summary = "我的评教总览", description = "教师查看本人任教课程的评分与留言")
    @PreAuthorize("hasAuthority('evaluation:read')")
    @GetMapping("/me")
    public ApiResponse<EvaluationOverview> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(evaluationService.myOverview(user));
    }
}
