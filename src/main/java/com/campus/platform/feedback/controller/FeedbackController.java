package com.campus.platform.feedback.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.feedback.dto.FeedbackCreateRequest;
import com.campus.platform.feedback.dto.FeedbackQuery;
import com.campus.platform.feedback.dto.FeedbackReplyRequest;
import com.campus.platform.feedback.dto.FeedbackView;
import com.campus.platform.feedback.service.FeedbackService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "校长信箱", description = "全员提交投诉/建议，仅系统管理员受理与回复")
@RestController
@RequestMapping("/api/v1/feedbacks")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @Operation(summary = "提交投诉或建议", description = "需要 feedback:submit；可匿名")
    @PreAuthorize("hasAuthority('feedback:submit')")
    @PostMapping
    public ApiResponse<FeedbackView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                            @Valid @RequestBody FeedbackCreateRequest request) {
        return ApiResponse.ok(feedbackService.create(user, request));
    }

    @Operation(summary = "我提交的投诉建议")
    @PreAuthorize("hasAuthority('feedback:submit')")
    @GetMapping("/me")
    public ApiResponse<PageResult<FeedbackView>> mine(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @Valid @ParameterObject FeedbackQuery query) {
        return ApiResponse.ok(feedbackService.mine(user, query));
    }

    @Operation(summary = "全部投诉建议", description = "需要 ADMIN 角色且具备 feedback:handle；默认只看未关闭")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('feedback:handle')")
    @GetMapping
    public ApiResponse<PageResult<FeedbackView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @Valid @ParameterObject FeedbackQuery query) {
        return ApiResponse.ok(feedbackService.list(user, query));
    }

    @Operation(summary = "投诉建议详情", description = "本人可读；非本人仅系统管理员可查")
    @GetMapping("/{id}")
    public ApiResponse<FeedbackView> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(feedbackService.get(user, id));
    }

    @Operation(summary = "处理并回复", description = "需要 ADMIN 角色且具备 feedback:handle")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('feedback:handle')")
    @PostMapping("/{id}/reply")
    public ApiResponse<FeedbackView> reply(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                           @Valid @RequestBody FeedbackReplyRequest request) {
        return ApiResponse.ok(feedbackService.reply(user, id, request));
    }
}
