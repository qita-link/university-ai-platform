package com.campus.platform.knowledge.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.knowledge.dto.SpaceCreateRequest;
import com.campus.platform.knowledge.dto.SpaceMemberRequest;
import com.campus.platform.knowledge.dto.SpaceMemberRoleRequest;
import com.campus.platform.knowledge.dto.SpaceMemberView;
import com.campus.platform.knowledge.dto.SpaceQuery;
import com.campus.platform.knowledge.dto.SpaceUpdateRequest;
import com.campus.platform.knowledge.dto.SpaceView;
import com.campus.platform.knowledge.service.KnowledgeSpaceService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
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

@Tag(name = "知识库空间", description = "知识库空间、可见范围与成员授权")
@RestController
@RequestMapping("/api/v1/knowledge/spaces")
public class KnowledgeSpaceController {

    private final KnowledgeSpaceService spaceService;

    public KnowledgeSpaceController(KnowledgeSpaceService spaceService) {
        this.spaceService = spaceService;
    }

    @Operation(summary = "可访问的知识库", description = "需要 kb:space:read；只返回当前用户有权读取的空间")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @GetMapping
    public ApiResponse<PageResult<SpaceView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @Valid @ParameterObject SpaceQuery query) {
        return ApiResponse.ok(spaceService.list(user, query));
    }

    @Operation(summary = "知识库详情")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @GetMapping("/{id}")
    public ApiResponse<SpaceView> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(spaceService.get(user, id));
    }

    @Operation(summary = "创建知识库", description = "需要 kb:space:create；PUBLIC 仅管理员可建，COURSE 需指定课程且需为该课程任课教师或管理员")
    @PreAuthorize("hasAnyAuthority('kb:space:create', 'kb:space:manage_all')")
    @PostMapping
    public ApiResponse<SpaceView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                         @Valid @RequestBody SpaceCreateRequest request) {
        return ApiResponse.ok(spaceService.create(user, request));
    }

    @Operation(summary = "修改知识库", description = "所有者/管理者；需要回传 version")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @PutMapping("/{id}")
    public ApiResponse<SpaceView> update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                         @Valid @RequestBody SpaceUpdateRequest request) {
        return ApiResponse.ok(spaceService.update(user, id, request));
    }

    @Operation(summary = "删除知识库", description = "软删除；所有者/管理者")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        spaceService.delete(user, id);
        return ApiResponse.ok();
    }

    @Operation(summary = "成员列表", description = "所有者/管理者")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @GetMapping("/{id}/members")
    public ApiResponse<List<SpaceMemberView>> members(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @PathVariable long id) {
        return ApiResponse.ok(spaceService.listMembers(user, id));
    }

    @Operation(summary = "添加/更新成员", description = "所有者/管理者；重复添加会更新角色")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @PostMapping("/{id}/members")
    public ApiResponse<SpaceMemberView> addMember(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable long id,
                                                  @Valid @RequestBody SpaceMemberRequest request) {
        return ApiResponse.ok(spaceService.addMember(user, id, request));
    }

    @Operation(summary = "修改成员角色", description = "所有者/管理者；不能修改所有者")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @PutMapping("/{id}/members/{userId}")
    public ApiResponse<SpaceMemberView> updateMember(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @PathVariable long id, @PathVariable long userId,
                                                     @Valid @RequestBody SpaceMemberRoleRequest request) {
        return ApiResponse.ok(spaceService.updateMember(user, id, userId, request.memberRole()));
    }

    @Operation(summary = "移除成员", description = "所有者/管理者；不能移除所有者")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @DeleteMapping("/{id}/members/{userId}")
    public ApiResponse<Void> removeMember(@AuthenticationPrincipal AuthenticatedUser user,
                                          @PathVariable long id, @PathVariable long userId) {
        spaceService.removeMember(user, id, userId);
        return ApiResponse.ok();
    }
}
