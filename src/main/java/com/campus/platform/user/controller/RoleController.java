package com.campus.platform.user.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.user.dto.PermissionView;
import com.campus.platform.user.dto.RoleView;
import com.campus.platform.user.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "角色与权限", description = "内置角色与权限点（只读）")
@RestController
@RequestMapping("/api/v1")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "角色列表（含权限点）", description = "需要 user:read 或 role:manage")
    @PreAuthorize("hasAnyAuthority('user:read', 'role:manage')")
    @GetMapping("/roles")
    public ApiResponse<List<RoleView>> roles() {
        return ApiResponse.ok(roleService.listRoles());
    }

    @Operation(summary = "权限点列表", description = "需要 role:manage")
    @PreAuthorize("hasAuthority('role:manage')")
    @GetMapping("/permissions")
    public ApiResponse<List<PermissionView>> permissions() {
        return ApiResponse.ok(roleService.listPermissions());
    }
}
