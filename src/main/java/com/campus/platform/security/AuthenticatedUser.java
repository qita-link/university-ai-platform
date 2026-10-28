package com.campus.platform.security;

import java.util.Set;

/**
 * 当前登录用户（每个请求根据 JWT 中的用户 ID 从数据库重新加载，因此角色/权限/状态变更即时生效）。
 * <p>
 * 业务服务通过它做两类检查：
 * <ol>
 *   <li>功能权限：{@link #hasPermission(String)}，例如是否允许创建知识库；</li>
 *   <li>数据归属：结合 {@link #userId()}、{@link #orgUnitId()} 判断能否操作某条具体数据。</li>
 * </ol>
 * AI 工具调用同样使用该对象，绝不依据模型输出的文字判断身份。
 *
 * @param userId      用户 ID
 * @param username    登录名
 * @param displayName 显示名
 * @param orgUnitId   所属组织单元，可为空
 * @param roles       角色编码，如 ADMIN
 * @param permissions 权限点编码，如 kb:space:create
 */
public record AuthenticatedUser(
        Long userId,
        String username,
        String displayName,
        Long orgUnitId,
        Set<String> roles,
        Set<String> permissions) {

    public AuthenticatedUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    public boolean isAdmin() {
        return hasRole(Roles.ADMIN);
    }

    public boolean isTeacher() {
        return hasRole(Roles.TEACHER);
    }

    public boolean isStudent() {
        return hasRole(Roles.STUDENT);
    }
}
