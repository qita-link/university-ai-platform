package com.campus.platform.scheduling.mapper;

import com.campus.platform.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 排课模块的跨模块查询（只读）。
 * <p>
 * 教师复用 {@code sys_user} + 角色，行政班复用 {@code sys_org_unit(type=CLASS)}，
 * 不重复建设用户/组织实体；此处只提供按角色取教师的查询，其余场景走各模块既有 Mapper。
 */
@Mapper
public interface SchedulingQueryMapper {

    /** 状态为 ACTIVE 且拥有 TEACHER 角色的用户（教师）。 */
    @Select("""
            SELECT u.*
              FROM sys_user u
              JOIN sys_user_role ur ON ur.user_id = u.id
              JOIN sys_role r ON r.id = ur.role_id
             WHERE r.code = 'TEACHER' AND u.status = 'ACTIVE'
             ORDER BY u.id
            """)
    List<SysUser> selectActiveTeachers();
}
