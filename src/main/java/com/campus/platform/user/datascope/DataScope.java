package com.campus.platform.user.datascope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import java.util.Set;

/**
 * 按组织计算出的数据范围。
 * <ul>
 *   <li>{@link #all()}：不受组织限制（例如持有 manage_all 类权限的管理员）；</li>
 *   <li>{@link #none()}：没有任何组织范围（例如未分配组织的普通用户），查询时必须返回空结果；</li>
 *   <li>{@link #of(Set)}：限定在给定组织 ID 集合内。</li>
 * </ul>
 * 业务模块应把范围作为 SQL 条件（{@link #applyTo}），不要先查全量再在内存中过滤。
 *
 * @param unrestricted 是否不受限
 * @param orgIds       允许的组织 ID（unrestricted 为 true 时忽略）
 */
public record DataScope(boolean unrestricted, Set<Long> orgIds) {

    private static final DataScope ALL = new DataScope(true, Set.of());
    private static final DataScope NONE = new DataScope(false, Set.of());

    public DataScope {
        orgIds = orgIds == null ? Set.of() : Set.copyOf(orgIds);
    }

    public static DataScope all() {
        return ALL;
    }

    public static DataScope none() {
        return NONE;
    }

    public static DataScope of(Set<Long> orgIds) {
        return orgIds == null || orgIds.isEmpty() ? NONE : new DataScope(false, orgIds);
    }

    public boolean isEmpty() {
        return !unrestricted && orgIds.isEmpty();
    }

    /** 判断某个组织是否在范围内。orgId 为空（数据未归属任何组织）时，只有不受限的范围允许。 */
    public boolean allows(Long orgId) {
        if (unrestricted) {
            return true;
        }
        return orgId != null && orgIds.contains(orgId);
    }

    /**
     * 把范围追加为查询条件：不受限时不加条件；空范围时追加恒假条件；否则追加 {@code column IN (...)}。
     */
    public <T> LambdaQueryWrapper<T> applyTo(LambdaQueryWrapper<T> wrapper, SFunction<T, Long> orgColumn) {
        if (unrestricted) {
            return wrapper;
        }
        if (orgIds.isEmpty()) {
            return wrapper.apply("1 = 0");
        }
        return wrapper.in(orgColumn, orgIds);
    }
}
