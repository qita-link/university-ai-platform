package com.campus.platform.auth.controller;

import com.campus.platform.auth.dto.ChangePasswordRequest;
import com.campus.platform.auth.dto.CurrentUserResponse;
import com.campus.platform.auth.dto.LoginRequest;
import com.campus.platform.auth.dto.LoginResponse;
import com.campus.platform.auth.service.AuthService;
import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.TreeSet;

/**
 * 认证接口：登录、退出、修改本人密码、当前用户。
 */
@Tag(name = "认证", description = "登录、退出、修改密码与当前用户信息")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "登录", description = """
            用户名或密码错误统一返回 401 AUTH_BAD_CREDENTIALS；连续失败达到阈值后账号临时锁定（403 AUTH_ACCOUNT_LOCKED）；
            同一 IP / 同一 IP+用户名请求过于频繁返回 429，并带 Retry-After 响应头。""")
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse result = authService.login(request.username(), request.password());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }

    @Operation(summary = "退出登录", description = "使当前用户已签发的全部令牌失效（所有设备同时退出）")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthenticatedUser user) {
        authService.logout(user);
        return ApiResponse.ok();
    }

    @Operation(summary = "修改本人密码", description = "需要提供原密码。成功后旧令牌全部失效，响应中返回新令牌")
    @PutMapping("/password")
    public ResponseEntity<ApiResponse<LoginResponse>> changePassword(@AuthenticationPrincipal AuthenticatedUser user,
                                                                     @Valid @RequestBody ChangePasswordRequest request) {
        LoginResponse result = authService.changePassword(user, request.oldPassword(), request.newPassword());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }

    @Operation(summary = "获取当前登录用户", description = "返回当前用户的基本信息、角色与权限点（每次请求实时从数据库加载）")
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(new CurrentUserResponse(
                user.userId(),
                user.username(),
                user.displayName(),
                user.orgUnitId(),
                new TreeSet<>(user.roles()),
                new TreeSet<>(user.permissions())));
    }
}
