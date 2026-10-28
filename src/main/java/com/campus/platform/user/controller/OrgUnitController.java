package com.campus.platform.user.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.user.dto.OrgTreeNode;
import com.campus.platform.user.dto.OrgUnitRequest;
import com.campus.platform.user.dto.OrgUnitStatusRequest;
import com.campus.platform.user.dto.OrgUnitView;
import com.campus.platform.user.service.OrgUnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "组织单元", description = "学院 / 系 / 专业 / 班级树形结构")
@RestController
@RequestMapping("/api/v1/org-units")
public class OrgUnitController {

    private final OrgUnitService orgUnitService;

    public OrgUnitController(OrgUnitService orgUnitService) {
        this.orgUnitService = orgUnitService;
    }

    @Operation(summary = "组织树", description = "登录用户可查看启用的组织；includeDisabled=true 需要 org:manage")
    @GetMapping("/tree")
    public ApiResponse<List<OrgTreeNode>> tree(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ApiResponse.ok(orgUnitService.tree(user, includeDisabled));
    }

    @Operation(summary = "组织详情")
    @GetMapping("/{id}")
    public ApiResponse<OrgUnitView> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(orgUnitService.get(user, id));
    }

    @Operation(summary = "新建组织", description = "需要 org:manage")
    @PreAuthorize("hasAuthority('org:manage')")
    @PostMapping
    public ApiResponse<OrgUnitView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                           @Valid @RequestBody OrgUnitRequest request) {
        return ApiResponse.ok(orgUnitService.create(user, request));
    }

    @Operation(summary = "修改组织（含移动上级）", description = "需要 org:manage；不能移动到自身或下级之下")
    @PreAuthorize("hasAuthority('org:manage')")
    @PutMapping("/{id}")
    public ApiResponse<OrgUnitView> update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                           @Valid @RequestBody OrgUnitRequest request) {
        return ApiResponse.ok(orgUnitService.update(user, id, request));
    }

    @Operation(summary = "启用/停用组织", description = "需要 org:manage；停用前须先停用全部下级，启用时上级须为启用状态")
    @PreAuthorize("hasAuthority('org:manage')")
    @PatchMapping("/{id}/status")
    public ApiResponse<OrgUnitView> changeStatus(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @PathVariable long id,
                                                 @Valid @RequestBody OrgUnitStatusRequest request) {
        return ApiResponse.ok(orgUnitService.changeStatus(user, id, request.status()));
    }
}
