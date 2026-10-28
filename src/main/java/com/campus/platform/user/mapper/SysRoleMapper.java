package com.campus.platform.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.user.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /** 角色编码与权限点编码对（全部角色） */
    @Select("""
            SELECT r.code AS roleCode, p.code AS permissionCode
              FROM sys_role r
              JOIN sys_role_permission rp ON rp.role_id = r.id
              JOIN sys_permission p ON p.id = rp.permission_id
             ORDER BY r.id, p.id
            """)
    List<RolePermissionCode> selectAllRolePermissionCodes();

    /** 一组角色合计拥有的权限点编码 */
    @Select("""
            <script>
            SELECT DISTINCT p.code
              FROM sys_permission p
              JOIN sys_role_permission rp ON rp.permission_id = p.id
             WHERE rp.role_id IN
             <foreach collection="roleIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<String> selectPermissionCodesOfRoles(@Param("roleIds") Collection<Long> roleIds);

    record RolePermissionCode(String roleCode, String permissionCode) {
    }
}
