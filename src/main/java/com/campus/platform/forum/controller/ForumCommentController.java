package com.campus.platform.forum.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.forum.service.ForumCommentService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "论坛评论", description = "删除评论；顶级评论删除时级联删除其回复")
@RestController
@RequestMapping("/api/v1/forum/comments")
public class ForumCommentController {

    private final ForumCommentService commentService;

    public ForumCommentController(ForumCommentService commentService) {
        this.commentService = commentService;
    }

    @Operation(summary = "删除评论", description = "作者本人或版主；顶级评论级联软删除全部回复")
    @PreAuthorize("hasAuthority('forum:comment:write') or hasAuthority('forum:moderate')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        commentService.delete(user, id);
        return ApiResponse.ok();
    }
}
