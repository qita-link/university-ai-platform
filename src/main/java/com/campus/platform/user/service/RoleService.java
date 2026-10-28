package com.campus.platform.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.user.dto.PermissionView;
import com.campus.platform.user.dto.RoleView;
import com.campus.platform.user.entity.SysPermission;
import com.campus.platform.user.entity.SysRole;
import com.campus.platform.user.mapper.SysPermissionMapper;
import com.campus.platform.user.mapper.SysRoleMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 角色与权限点查询。第一版角色与权限点由迁移脚本维护（内置三种角色），不提供在线编辑。
 */
@Service
public class RoleService {

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;

    public RoleService(SysRoleMapper roleMapper, SysPermissionMapper permissionMapper) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
    }

    public List<RoleView> listRoles() {
        Map<String, List<String>> perms = new HashMap<>();
        roleMapper.selectAllRolePermissionCodes()
                .forEach(rp -> perms.computeIfAbsent(rp.roleCode(), k -> new ArrayList<>()).add(rp.permissionCode()));
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId)).stream()
                .map(r -> new RoleView(r.getId(), r.getCode(), r.getName(), r.getDescription(),
                        Boolean.TRUE.equals(r.getBuiltIn()), List.copyOf(perms.getOrDefault(r.getCode(), List.of()))))
                .toList();
    }

    public List<PermissionView> listPermissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getId))
                .stream().map(PermissionView::from).toList();
    }

    /**
     * 把角色编码解析为角色实体；存在未知编码时返回 400，并列出未知编码。
     */
    public List<SysRole> resolve(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>().in(SysRole::getCode, codes));
        if (roles.size() != new HashSet<>(codes).size()) {
            Set<String> unknown = new TreeSet<>(codes);
            roles.forEach(r -> unknown.remove(r.getCode()));
            throw BusinessException.badRequest("角色不存在: " + String.join(",", unknown));
        }
        return roles;
    }

    /** 一组角色合计拥有的权限点编码 */
    public Set<String> permissionsOf(Collection<SysRole> roles) {
        if (roles.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(roleMapper.selectPermissionCodesOfRoles(roles.stream().map(SysRole::getId).toList()));
    }
}
