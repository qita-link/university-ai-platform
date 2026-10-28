package com.campus.platform.user.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.user.dto.PasswordResetRequest;
import com.campus.platform.user.dto.UserCreateRequest;
import com.campus.platform.user.dto.UserQuery;
import com.campus.platform.user.dto.UserRolesRequest;
import com.campus.platform.user.dto.UserStatusRequest;
import com.campus.platform.user.dto.UserUpdateRequest;
import com.campus.platform.user.dto.UserView;
import com.campus.platform.user.service.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户管理", description = "管理员管理账号、状态、角色与密码")
@RestController
@RequestMapping("/api/v1/users")
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @Operation(summary = "分页查询用户", description = "需要 user:read")
    @PreAuthorize("hasAuthority('user:read')")
    @GetMapping
    public ApiResponse<PageResult<UserView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @Valid @ParameterObject UserQuery query) {
        return ApiResponse.ok(userAdminService.list(user, query));
    }

    @Operation(summary = "用户详情", description = "需要 user:read")
    @PreAuthorize("hasAuthority('user:read')")
    @GetMapping("/{id}")
    public ApiResponse<UserView> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(userAdminService.get(user, id));
    }

    @Operation(summary = "创建用户", description = "需要 user:manage 与 role:manage")
    @PreAuthorize("hasAuthority('user:manage') and hasAuthority('role:manage')")
    @PostMapping
    public ApiResponse<UserView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                        @Valid @RequestBody UserCreateRequest request) {
        return ApiResponse.ok(userAdminService.create(user, request));
    }

    @Operation(summary = "修改用户资料", description = "需要 user:manage；需回传 version（乐观锁），冲突返回 409")
    @PreAuthorize("hasAuthority('user:manage')")
    @PutMapping("/{id}")
    public ApiResponse<UserView> update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                        @Valid @RequestBody UserUpdateRequest request) {
        return ApiResponse.ok(userAdminService.update(user, id, request));
    }

    @Operation(summary = "启用/停用/锁定账号", description = """
            需要 user:manage。不能停用或锁定自己；不能停用或锁定最后一个可用管理员。
            停用/锁定会使该用户的令牌立即失效；启用同时解除连续登录失败的临时锁定。""")
    @PreAuthorize("hasAuthority('user:manage')")
    @PatchMapping("/{id}/status")
    public ApiResponse<UserView> changeStatus(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                              @Valid @RequestBody UserStatusRequest request) {
        return ApiResponse.ok(userAdminService.changeStatus(user, id, request.status(), request.reason()));
    }

    @Operation(summary = "解除临时锁定", description = "需要 user:manage；清零连续登录失败计数")
    @PreAuthorize("hasAuthority('user:manage')")
    @PostMapping("/{id}/unlock")
    public ApiResponse<UserView> unlock(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return ApiResponse.ok(userAdminService.unlock(user, id));
    }

    @Operation(summary = "分配角色（整体替换）", description = "需要 role:manage。不能修改自己的角色；不能移除最后一个可用管理员的 ADMIN 角色")
    @PreAuthorize("hasAuthority('role:manage')")
    @PutMapping("/{id}/roles")
    public ApiResponse<UserView> assignRoles(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                             @Valid @RequestBody UserRolesRequest request) {
        return ApiResponse.ok(userAdminService.assignRoles(user, id, request.roleCodes()));
    }

    @Operation(summary = "重置密码", description = "需要 user:manage；不能重置自己的密码（请使用 PUT /auth/password）。重置后该用户令牌全部失效")
    @PreAuthorize("hasAuthority('user:manage')")
    @PostMapping("/{id}/password-reset")
    public ApiResponse<Void> resetPassword(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                           @Valid @RequestBody PasswordResetRequest request) {
        userAdminService.resetPassword(user, id, request.newPassword());
        return ApiResponse.ok();
    }
}
