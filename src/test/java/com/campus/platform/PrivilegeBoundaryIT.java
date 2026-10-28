package com.campus.platform;

import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.security.Roles;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.dto.UserCreateRequest;
import com.campus.platform.user.dto.UserUpdateRequest;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.service.UserAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段二：用户管理的权限边界（Service 层，供 REST 与后续 AI 工具共用）。
 * <p>
 * 仅有 user:manage/role:manage 的账号不能管理权限更高的用户，也不能授予自己不具备的权限。
 */
class PrivilegeBoundaryIT extends IntegrationTestBase {

    @Autowired
    UserAdminService userAdminService;

    @Test
    void operatorCannotManageHigherPrivilegedUserOrGrantHigherRole() {
        SysUser admin = createUser("pb_admin", Roles.ADMIN);
        SysUser teacher = createUser("pb_teacher", Roles.TEACHER);
        SysUser anotherTeacher = createUser("pb_teacher2", Roles.TEACHER);
        AuthenticatedUser operator = limitedManager(teacher);

        // 目标管理员拥有 operator 没有的权限点（如 audit:read）
        assertForbidden(() -> userAdminService.changeStatus(operator, admin.getId(), UserStatus.DISABLED, null));
        assertForbidden(() -> userAdminService.update(operator, admin.getId(),
                new UserUpdateRequest("改名", null, null, null, admin.getVersion())));
        assertForbidden(() -> userAdminService.assignRoles(operator, admin.getId(), Set.of(Roles.TEACHER)));

        // 不能创建管理员，也不能给低权限用户授予 ADMIN
        assertForbidden(() -> userAdminService.create(operator, new UserCreateRequest(
                "pb_new_" + suffix(), "新用户", "Init-pass-2026", null, null, null, Set.of(Roles.ADMIN))));
        assertForbidden(() -> userAdminService.assignRoles(operator, anotherTeacher.getId(), Set.of(Roles.ADMIN)));

        // 在自身权限范围内的操作仍可执行：移除教师角色（空集合表示不授予任何角色）
        userAdminService.assignRoles(operator, anotherTeacher.getId(), Set.of());
        assertThat(userMapper.selectRoleCodes(anotherTeacher.getId())).isEmpty();

        // 目标管理员未被修改
        assertThat(userMapper.selectById(admin.getId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(userMapper.selectRoleCodes(admin.getId())).containsExactly(Roles.ADMIN);
    }

    @Test
    void operatorCannotEscalateThroughUpdateOrPasswordReset() {
        SysUser admin = createUser("pb_admin2", Roles.ADMIN);
        SysUser teacher = createUser("pb_teacher3", Roles.TEACHER);
        SysUser lowTeacher = createUser("pb_low_teacher", Roles.TEACHER);
        AuthenticatedUser operator = limitedManager(teacher);

        assertForbidden(() -> userAdminService.resetPassword(operator, admin.getId(), "Reset-pass-2026"));
        assertForbidden(() -> userAdminService.unlock(operator, admin.getId()));

        // 同等权限的目标可以正常管理（说明失败原因不是“没有 user:manage”）
        userAdminService.changeStatus(operator, lowTeacher.getId(), UserStatus.DISABLED, "权限边界测试");
        assertThat(userMapper.selectById(lowTeacher.getId()).getStatus()).isEqualTo(UserStatus.DISABLED);
    }

    private AuthenticatedUser limitedManager(SysUser teacher) {
        AuthenticatedUser base = principalOf(teacher);
        Set<String> permissions = new HashSet<>(base.permissions());
        permissions.add(Permissions.USER_MANAGE);
        permissions.add(Permissions.ROLE_MANAGE);
        return new AuthenticatedUser(base.userId(), base.username(), base.displayName(), null,
                Set.of(Roles.TEACHER), permissions);
    }

    private static void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
