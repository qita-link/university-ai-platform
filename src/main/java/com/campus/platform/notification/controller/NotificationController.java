package com.campus.platform.notification.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageQuery;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.notification.dto.DeliveryView;
import com.campus.platform.notification.dto.NotificationView;
import com.campus.platform.notification.entity.NotificationCategory;
import com.campus.platform.notification.service.NotificationService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "通知", description = "站内通知：未读数、列表、已读")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "我的通知", description = "只返回本人的通知，可按未读与分类筛选")
    @GetMapping
    public ApiResponse<PageResult<NotificationView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                          @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                          @RequestParam(required = false) NotificationCategory category,
                                                          @Valid @ParameterObject PageQuery query) {
        return ApiResponse.ok(notificationService.list(user, unreadOnly, category, query));
    }

    @Operation(summary = "待弹窗的公告", description = "未读且要求弹窗的官方公告，按发布顺序取前几条")
    @GetMapping("/popups")
    public ApiResponse<List<NotificationView>> popups(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.ok(notificationService.popups(user, limit));
    }

    @Operation(summary = "未读数", description = "手机端红点用，轮询成本很低")
    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(notificationService.unreadCount(user));
    }

    @Operation(summary = "标记一条为已读")
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        notificationService.markRead(user, id);
        return ApiResponse.ok();
    }

    @Operation(summary = "全部标为已读")
    @PostMapping("/read-all")
    public ApiResponse<Integer> markAllRead(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(notificationService.markAllRead(user));
    }

    @Operation(summary = "投递记录", description = "需要 system:config；查看邮件/微信渠道的投递状态")
    @PreAuthorize("hasAuthority('system:config')")
    @GetMapping("/deliveries")
    public ApiResponse<PageResult<DeliveryView>> deliveries(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @Valid @ParameterObject PageQuery query) {
        return ApiResponse.ok(notificationService.deliveries(user, query));
    }
}
