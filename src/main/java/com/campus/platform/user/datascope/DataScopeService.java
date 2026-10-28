package com.campus.platform.user.datascope;

import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 通用数据权限计算，供知识库、课程、请假等模块复用。
 * <p>
 * 只回答“组织层面”的问题，例如：
 * <ul>
 *   <li>用户能管理哪些组织的数据（{@link #manageableScope}）：本人组织及全部下级组织，
 *       持有指定“全局”权限点时不受限；</li>
 *   <li>“对某组织及其下级开放”的资源，哪些对当前用户可见（{@link #membershipOrgIds}）：
 *       资源绑定的组织必须是用户所属组织本身或其上级，例如学院级知识库对学院下所有班级的学生可见。</li>
 * </ul>
 * 资源归属（作者本人、课程任课教师、知识库成员等）由各业务服务在此基础上自行判断。
 * <p>
 * 计算基于组织树结构，不区分组织状态：组织停用不会让历史数据失去归属，
 * 资源是否因组织停用而隐藏由业务模块决定。
 * 每次调用都会查询数据库，组织变化立即生效；同一请求内需要多次使用时，调用方应保存结果复用。
 */
@Service
public class DataScopeService {

    private final SysOrgUnitMapper orgMapper;

    public DataScopeService(SysOrgUnitMapper orgMapper) {
        this.orgMapper = orgMapper;
    }

    /** 组织本身及全部下级组织 ID。组织不存在时返回空集合。 */
    public Set<Long> orgAndDescendants(Long orgId) {
        if (orgId == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(orgMapper.selectSubtreeIds(orgId));
    }

    /** 组织本身及全部上级组织 ID（从自身到根）。组织不存在时返回空集合。 */
    public Set<Long> orgAndAncestors(Long orgId) {
        if (orgId == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(orgMapper.selectAncestorIds(orgId));
    }

    /**
     * 用户可管理的组织范围。
     *
     * @param globalPermission 持有该权限点时不受组织限制（如 {@code course:manage_all}）；可为空
     */
    public DataScope manageableScope(AuthenticatedUser user, String globalPermission) {
        if (user == null) {
            return DataScope.none();
        }
        if (globalPermission != null && user.hasPermission(globalPermission)) {
            return DataScope.all();
        }
        return DataScope.of(orgAndDescendants(user.orgUnitId()));
    }

    /**
     * 用户所属组织及其全部上级。用于判断“对组织 X 及其下级开放”的资源：X 在此集合中即可见。
     * 未分配组织的用户返回空集合。
     */
    public Set<Long> membershipOrgIds(AuthenticatedUser user) {
        return user == null ? Set.of() : orgAndAncestors(user.orgUnitId());
    }

    /** 用户是否属于组织 orgId 或其下级组织。 */
    public boolean isMemberOf(AuthenticatedUser user, Long orgId) {
        return orgId != null && membershipOrgIds(user).contains(orgId);
    }

    /**
     * 断言组织 orgId 在用户的可管理范围内，否则抛出 FORBIDDEN。
     */
    public void requireManageable(AuthenticatedUser user, Long orgId, String globalPermission) {
        if (!manageableScope(user, globalPermission).allows(orgId)) {
            throw BusinessException.forbidden("无权管理该组织的数据");
        }
    }
}
