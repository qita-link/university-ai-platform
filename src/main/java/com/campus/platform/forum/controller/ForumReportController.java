package com.campus.platform.forum.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.forum.dto.ForumReportCreateRequest;
import com.campus.platform.forum.dto.ForumReportHandleRequest;
import com.campus.platform.forum.dto.ForumReportView;
import com.campus.platform.forum.service.ForumReportService;
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

/**
 * 论坛举报与审核队列。
 */
@Tag(name = "论坛举报", description = "举报内容、审核队列与处理")
@RestController
@RequestMapping("/api/v1/forum")
public class ForumReportController {

    private final ForumReportService reportService;

    public ForumReportController(ForumReportService reportService) {
        this.reportService = reportService;
    }

    @Operation(summary = "举报帖子或评论", description = """
            登录即可举报。同一个人对同一目标重复举报是幂等的（返回原记录 ID，不报错）。""")
    @PostMapping("/reports")
    public ApiResponse<Long> report(@AuthenticationPrincipal AuthenticatedUser user,
                                    @Valid @RequestBody ForumReportCreateRequest request) {
        return ApiResponse.ok(reportService.report(user, request));
    }

    @Operation(summary = "审核队列", description = "需要 forum:moderate。status 可选 PENDING/ACCEPTED/REJECTED，不传为全部")
    @PreAuthorize("hasAuthority('forum:moderate')")
    @GetMapping("/reports")
    public ApiResponse<PageResult<ForumReportView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "1") long page,
                                                         @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(reportService.list(user, status, page, size));
    }

    @Operation(summary = "处理举报", description = """
            需要 forum:moderate。accept=true 时帖子转为隐藏、评论软删除；已处理过的举报返回 409。""")
    @PreAuthorize("hasAuthority('forum:moderate')")
    @PostMapping("/reports/{id}/handle")
    public ApiResponse<Void> handle(@AuthenticationPrincipal AuthenticatedUser user,
                                    @PathVariable long id,
                                    @Valid @RequestBody ForumReportHandleRequest request) {
        reportService.handle(user, id, request.accept(), request.note());
        return ApiResponse.ok();
    }
}
