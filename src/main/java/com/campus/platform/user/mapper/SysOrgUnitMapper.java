package com.campus.platform.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.user.entity.SysOrgUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysOrgUnitMapper extends BaseMapper<SysOrgUnit> {

    /** 防御性递归深度上限：类型规则限制树最多 4 层，这里只防止脏数据导致无限递归 */
    int MAX_DEPTH = 32;

    /**
     * 锁定并读取整棵组织树（按 id 顺序加锁）。
     * <p>
     * 所有改变树结构或状态的操作（新建、修改上级、启用/停用）都先执行它：
     * 并发的结构变更因此串行执行，环检测、上下级状态检查都基于最新的已提交数据。
     * 组织单元数量在千级以内，且只有管理员会修改，整表加锁的代价可以接受。
     */
    @Select("SELECT * FROM sys_org_unit ORDER BY id FOR UPDATE")
    List<SysOrgUnit> lockAll();

    /** 组织自身及全部下级组织的 ID（不区分状态） */
    @Select("""
            WITH RECURSIVE sub (id, depth) AS (
                SELECT id, 0 FROM sys_org_unit WHERE id = #{orgId}
                UNION ALL
                SELECT c.id, sub.depth + 1 FROM sys_org_unit c JOIN sub ON c.parent_id = sub.id
                 WHERE sub.depth < 32
            )
            SELECT id FROM sub
            """)
    List<Long> selectSubtreeIds(@Param("orgId") long orgId);

    /** 组织自身及全部上级组织的 ID（不区分状态） */
    @Select("""
            WITH RECURSIVE anc (id, parent_id, depth) AS (
                SELECT id, parent_id, 0 FROM sys_org_unit WHERE id = #{orgId}
                UNION ALL
                SELECT p.id, p.parent_id, anc.depth + 1 FROM sys_org_unit p JOIN anc ON p.id = anc.parent_id
                 WHERE anc.depth < 32
            )
            SELECT id FROM anc
            """)
    List<Long> selectAncestorIds(@Param("orgId") long orgId);
}
