package com.campus.platform.forum.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.forum.dto.ForumCategoryRequest;
import com.campus.platform.forum.dto.ForumCategoryView;
import com.campus.platform.forum.service.ForumCategoryService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "论坛分类", description = "论坛板块分类，写操作仅管理员")
@RestController
@RequestMapping("/api/v1/forum/categories")
public class ForumCategoryController {

    private final ForumCategoryService categoryService;

    public ForumCategoryController(ForumCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "分类列表", description = "登录可读；includeDisabled=true 需要 forum:category:manage")
    @GetMapping
    public ApiResponse<List<ForumCategoryView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ApiResponse.ok(categoryService.list(user, includeDisabled));
    }

    @Operation(summary = "分类详情", description = "停用分类对普通用户返回 404")
    @GetMapping("/{id}")
    public ApiResponse<ForumCategoryView> get(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable long id) {
        return ApiResponse.ok(categoryService.get(user, id));
    }

    @Operation(summary = "新建分类", description = "需要 forum:category:manage")
    @PreAuthorize("hasAuthority('forum:category:manage')")
    @PostMapping
    public ApiResponse<ForumCategoryView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @Valid @RequestBody ForumCategoryRequest request) {
        return ApiResponse.ok(categoryService.create(user, request));
    }

    @Operation(summary = "修改分类", description = "需要 forum:category:manage；可停用/启用")
    @PreAuthorize("hasAuthority('forum:category:manage')")
    @PutMapping("/{id}")
    public ApiResponse<ForumCategoryView> update(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @PathVariable long id,
                                                 @Valid @RequestBody ForumCategoryRequest request) {
        return ApiResponse.ok(categoryService.update(user, id, request));
    }

    @Operation(summary = "删除分类", description = "需要 forum:category:manage；分类下不能有帖子（含已删除）")
    @PreAuthorize("hasAuthority('forum:category:manage')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        categoryService.delete(user, id);
        return ApiResponse.ok();
    }
}
