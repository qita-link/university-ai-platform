package com.campus.platform.forum.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageQuery;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.forum.dto.ForumCommentCreateRequest;
import com.campus.platform.forum.dto.ForumCommentThreadView;
import com.campus.platform.forum.dto.ForumCommentView;
import com.campus.platform.forum.dto.ForumPostCreateRequest;
import com.campus.platform.forum.dto.ForumPostDetailView;
import com.campus.platform.forum.dto.ForumPostQuery;
import com.campus.platform.forum.dto.ForumPostStatusRequest;
import com.campus.platform.forum.dto.ForumPostSummaryView;
import com.campus.platform.forum.dto.ForumPostUpdateRequest;
import com.campus.platform.forum.service.ForumCommentService;
import com.campus.platform.forum.service.ForumPostService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "论坛帖子", description = "发帖、编辑、软删除、关键词检索、版主管理与评论")
@RestController
@RequestMapping("/api/v1/forum/posts")
public class ForumPostController {

    private final ForumPostService postService;
    private final ForumCommentService commentService;

    public ForumPostController(ForumPostService postService, ForumCommentService commentService) {
        this.postService = postService;
        this.commentService = commentService;
    }

    @Operation(summary = "帖子列表/检索", description = "登录可读；默认只返回 PUBLISHED；status=HIDDEN 仅版主可用")
    @GetMapping
    public ApiResponse<PageResult<ForumPostSummaryView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                              @Valid @ParameterObject ForumPostQuery query) {
        return ApiResponse.ok(postService.list(user, query));
    }

    @Operation(summary = "帖子详情", description = "隐藏帖子仅作者本人和版主可见；已删除/无权读取返回 404")
    @GetMapping("/{id}")
    public ApiResponse<ForumPostDetailView> get(@AuthenticationPrincipal AuthenticatedUser user,
                                                @PathVariable long id) {
        return ApiResponse.ok(postService.get(user, id));
    }

    @Operation(summary = "发帖", description = "需要 forum:post:write")
    @PreAuthorize("hasAuthority('forum:post:write')")
    @PostMapping
    public ApiResponse<ForumPostDetailView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @Valid @RequestBody ForumPostCreateRequest request) {
        return ApiResponse.ok(postService.create(user, request));
    }

    @Operation(summary = "修改帖子", description = "作者本人或版主；需要回传 version")
    @PreAuthorize("hasAuthority('forum:post:write') or hasAuthority('forum:moderate')")
    @PutMapping("/{id}")
    public ApiResponse<ForumPostDetailView> update(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @PathVariable long id,
                                                   @Valid @RequestBody ForumPostUpdateRequest request) {
        return ApiResponse.ok(postService.update(user, id, request));
    }

    @Operation(summary = "删除帖子", description = "软删除；作者本人或版主")
    @PreAuthorize("hasAuthority('forum:post:write') or hasAuthority('forum:moderate')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        postService.delete(user, id);
        return ApiResponse.ok();
    }

    @Operation(summary = "隐藏/恢复/置顶", description = "需要 forum:moderate；status 与 pinned 至少提供一个")
    @PreAuthorize("hasAuthority('forum:moderate')")
    @PatchMapping("/{id}/status")
    public ApiResponse<Void> moderate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                      @Valid @RequestBody ForumPostStatusRequest request) {
        postService.moderate(user, id, request);
        return ApiResponse.ok();
    }

    @Operation(summary = "评论线程", description = "按顶级评论分页，每个线程包含全部回复")
    @GetMapping("/{id}/comments")
    public ApiResponse<PageResult<ForumCommentThreadView>> comments(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
            @Valid @ParameterObject PageQuery query) {
        return ApiResponse.ok(commentService.list(user, id, query));
    }

    @Operation(summary = "发表评论/回复", description = "需要 forum:comment:write；parentId 为空表示顶级评论")
    @PreAuthorize("hasAuthority('forum:comment:write')")
    @PostMapping("/{id}/comments")
    public ApiResponse<ForumCommentView> comment(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @PathVariable long id,
                                                 @Valid @RequestBody ForumCommentCreateRequest request) {
        return ApiResponse.ok(commentService.create(user, id, request));
    }
}
