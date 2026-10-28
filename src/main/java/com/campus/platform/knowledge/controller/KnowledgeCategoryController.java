package com.campus.platform.knowledge.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.knowledge.dto.CategoryNode;
import com.campus.platform.knowledge.dto.CategoryRequest;
import com.campus.platform.knowledge.service.KnowledgeCategoryService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "知识库分类", description = "知识库目录分类（最多两级）")
@RestController
@RequestMapping("/api/v1/knowledge/categories")
public class KnowledgeCategoryController {

    private final KnowledgeCategoryService categoryService;

    public KnowledgeCategoryController(KnowledgeCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "分类树", description = "所有拥有 kb:space:read 的登录用户可读")
    @GetMapping
    public ApiResponse<List<CategoryNode>> tree(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(categoryService.tree(user));
    }

    @Operation(summary = "新建分类", description = "需要 kb:category:manage")
    @PreAuthorize("hasAuthority('kb:category:manage')")
    @PostMapping
    public ApiResponse<CategoryNode> create(@AuthenticationPrincipal AuthenticatedUser user,
                                            @Valid @RequestBody CategoryRequest request) {
        return ApiResponse.ok(categoryService.create(user, request));
    }

    @Operation(summary = "修改分类", description = "需要 kb:category:manage；有子分类时不能移动")
    @PreAuthorize("hasAuthority('kb:category:manage')")
    @PutMapping("/{id}")
    public ApiResponse<CategoryNode> update(@AuthenticationPrincipal AuthenticatedUser user,
                                            @PathVariable long id,
                                            @Valid @RequestBody CategoryRequest request) {
        return ApiResponse.ok(categoryService.update(user, id, request));
    }

    @Operation(summary = "删除分类", description = "需要 kb:category:manage；分类下不能有子分类或知识库")
    @PreAuthorize("hasAuthority('kb:category:manage')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        categoryService.delete(user, id);
        return ApiResponse.ok();
    }
}
