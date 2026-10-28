package com.campus.platform.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.dto.OrgTreeNode;
import com.campus.platform.user.dto.OrgUnitRequest;
import com.campus.platform.user.dto.OrgUnitView;
import com.campus.platform.user.entity.OrgStatus;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 组织单元（树形）管理。
 * <p>
 * 结构规则：
 * <ul>
 *   <li>下级组织的类型层级必须低于上级（{@code OrgType.canContain}），因此树最多 4 层且天然无环；
 *       修改上级时仍然显式检查“新上级不能是自己或自己的下级”，给出明确的错误提示；</li>
 *   <li>新建或挂到某个上级下时，该上级必须是启用状态；</li>
 *   <li>停用组织前，其下级组织必须已全部停用；启用组织时，其上级必须是启用状态。
 *       因此“启用的组织，其所有上级都是启用的”始终成立；</li>
 *   <li>组织不物理删除（用户与业务数据通过外键引用组织）。</li>
 * </ul>
 * 所有结构变更先锁定整棵树（{@link SysOrgUnitMapper#lockAll()}），并发变更串行执行。
 */
@Service
public class OrgUnitService {

    static final String MODULE = "user";

    private final SysOrgUnitMapper orgMapper;
    private final AuditService auditService;
    private final Clock clock;

    public OrgUnitService(SysOrgUnitMapper orgMapper, AuditService auditService, Clock clock) {
        this.orgMapper = orgMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---------------- 查询 ----------------

    /**
     * 组织树。所有登录用户都可以查看启用的组织（选择班级、知识库可见范围等场景需要）；
     * 只有持有 org:manage 的用户可以查看已停用的组织。
     */
    public List<OrgTreeNode> tree(AuthenticatedUser user, boolean includeDisabled) {
        if (includeDisabled) {
            Permissions.require(user, Permissions.ORG_MANAGE);
        }
        List<SysOrgUnit> all = orgMapper.selectList(new LambdaQueryWrapper<SysOrgUnit>()
                .eq(!includeDisabled, SysOrgUnit::getStatus, OrgStatus.ACTIVE));
        return buildTree(all);
    }

    public OrgUnitView get(AuthenticatedUser user, long id) {
        SysOrgUnit org = orgMapper.selectById(id);
        if (org == null || (org.getStatus() != OrgStatus.ACTIVE && !user.hasPermission(Permissions.ORG_MANAGE))) {
            throw BusinessException.notFound("组织");
        }
        return OrgUnitView.from(org);
    }

    // ---------------- 变更 ----------------

    @Transactional
    public OrgUnitView create(AuthenticatedUser operator, OrgUnitRequest req) {
        Permissions.require(operator, Permissions.ORG_MANAGE);
        Map<Long, SysOrgUnit> tree = lockTree();
        String code = req.code().trim();
        requireCodeUnique(tree, code, null);
        if (req.parentId() != null) {
            SysOrgUnit parent = requireActiveParent(tree, req.parentId());
            requireTypeFits(parent, req);
        }
        SysOrgUnit org = new SysOrgUnit();
        org.setParentId(req.parentId());
        org.setCode(code);
        org.setName(req.name().trim());
        org.setType(req.type());
        org.setStatus(OrgStatus.ACTIVE);
        org.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        orgMapper.insert(org);
        audit("ORG_CREATE", org.getId(), "code", code, "parentId", req.parentId());
        return OrgUnitView.from(orgMapper.selectById(org.getId()));
    }

    @Transactional
    public OrgUnitView update(AuthenticatedUser operator, long id, OrgUnitRequest req) {
        Permissions.require(operator, Permissions.ORG_MANAGE);
        Map<Long, SysOrgUnit> tree = lockTree();
        SysOrgUnit org = tree.get(id);
        if (org == null) {
            throw BusinessException.notFound("组织");
        }
        String code = req.code().trim();
        requireCodeUnique(tree, code, id);

        Long newParentId = req.parentId();
        if (newParentId != null) {
            if (newParentId == id || descendantsOf(tree, id).contains(newParentId)) {
                throw new BusinessException(ErrorCode.ORG_INVALID_PARENT, "不能把组织移动到自身或其下级组织之下");
            }
            SysOrgUnit parent = Objects.equals(newParentId, org.getParentId())
                    ? tree.get(newParentId) : requireActiveParent(tree, newParentId);
            if (parent == null) {
                throw new BusinessException(ErrorCode.ORG_INVALID_PARENT, "上级组织不存在");
            }
            requireTypeFits(parent, req);
            if (org.getStatus() == OrgStatus.ACTIVE && parent.getStatus() != OrgStatus.ACTIVE) {
                throw new BusinessException(ErrorCode.ORG_INVALID_PARENT, "不能把启用的组织挂到已停用的组织下");
            }
        }
        // 类型变化后，现有下级必须仍然满足层级规则
        for (SysOrgUnit child : tree.values()) {
            if (Objects.equals(child.getParentId(), id) && !req.type().canContain(child.getType())) {
                throw new BusinessException(ErrorCode.ORG_INVALID_PARENT,
                        "修改后的类型（" + req.type().label() + "）不能包含现有下级组织“" + child.getName() + "”");
            }
        }

        orgMapper.update(null, new LambdaUpdateWrapper<SysOrgUnit>()
                .eq(SysOrgUnit::getId, id)
                .set(SysOrgUnit::getParentId, newParentId)
                .set(SysOrgUnit::getCode, code)
                .set(SysOrgUnit::getName, req.name().trim())
                .set(SysOrgUnit::getType, req.type())
                .set(SysOrgUnit::getSortOrder, req.sortOrder() == null ? 0 : req.sortOrder())
                .set(SysOrgUnit::getUpdatedAt, LocalDateTime.now(clock)));
        audit("ORG_UPDATE", id, "code", code, "parentId", newParentId, "oldParentId", org.getParentId());
        return OrgUnitView.from(orgMapper.selectById(id));
    }

    @Transactional
    public OrgUnitView changeStatus(AuthenticatedUser operator, long id, OrgStatus target) {
        Permissions.require(operator, Permissions.ORG_MANAGE);
        Map<Long, SysOrgUnit> tree = lockTree();
        SysOrgUnit org = tree.get(id);
        if (org == null) {
            throw BusinessException.notFound("组织");
        }
        if (org.getStatus() == target) {
            return OrgUnitView.from(org);
        }
        if (target == OrgStatus.DISABLED) {
            boolean hasActiveChild = tree.values().stream()
                    .anyMatch(c -> Objects.equals(c.getParentId(), id) && c.getStatus() == OrgStatus.ACTIVE);
            if (hasActiveChild) {
                throw new BusinessException(ErrorCode.ORG_HAS_ACTIVE_CHILDREN, "请先停用该组织的全部下级组织");
            }
        } else if (org.getParentId() != null) {
            SysOrgUnit parent = tree.get(org.getParentId());
            if (parent == null || parent.getStatus() != OrgStatus.ACTIVE) {
                throw new BusinessException(ErrorCode.ORG_INVALID_PARENT, "上级组织已停用，请先启用上级组织");
            }
        }
        orgMapper.update(null, new LambdaUpdateWrapper<SysOrgUnit>()
                .eq(SysOrgUnit::getId, id)
                .set(SysOrgUnit::getStatus, target)
                .set(SysOrgUnit::getUpdatedAt, LocalDateTime.now(clock)));
        audit(target == OrgStatus.DISABLED ? "ORG_DISABLE" : "ORG_ENABLE", id, "code", org.getCode());
        return OrgUnitView.from(orgMapper.selectById(id));
    }

    // ---------------- 供其他服务使用 ----------------

    /** 断言组织存在且已启用（为用户分配组织等场景）。 */
    public void requireActive(Long orgId) {
        if (orgId == null) {
            return;
        }
        SysOrgUnit org = orgMapper.selectById(orgId);
        if (org == null || org.getStatus() != OrgStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ORG_UNAVAILABLE);
        }
    }

    // ---------------- helpers ----------------

    private Map<Long, SysOrgUnit> lockTree() {
        Map<Long, SysOrgUnit> map = new HashMap<>();
        for (SysOrgUnit o : orgMapper.lockAll()) {
            map.put(o.getId(), o);
        }
        return map;
    }

    private static void requireCodeUnique(Map<Long, SysOrgUnit> tree, String code, Long selfId) {
        // 排序规则不区分大小写，这里同样忽略大小写，给出明确提示（唯一索引作为最终保证）
        boolean taken = tree.values().stream()
                .anyMatch(o -> o.getCode().equalsIgnoreCase(code) && !o.getId().equals(selfId));
        if (taken) {
            throw BusinessException.conflict("组织编码已存在");
        }
    }

    private static SysOrgUnit requireActiveParent(Map<Long, SysOrgUnit> tree, long parentId) {
        SysOrgUnit parent = tree.get(parentId);
        if (parent == null) {
            throw new BusinessException(ErrorCode.ORG_INVALID_PARENT, "上级组织不存在");
        }
        if (parent.getStatus() != OrgStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ORG_INVALID_PARENT, "上级组织已停用");
        }
        return parent;
    }

    private static void requireTypeFits(SysOrgUnit parent, OrgUnitRequest req) {
        if (!parent.getType().canContain(req.type())) {
            throw new BusinessException(ErrorCode.ORG_INVALID_PARENT,
                    parent.getType().label() + "下不能设置" + req.type().label());
        }
    }

    /** id 的全部下级（不含自身），基于已锁定的树在内存中计算，带访问集合防御脏数据中的环 */
    static Set<Long> descendantsOf(Map<Long, SysOrgUnit> tree, long id) {
        Map<Long, List<Long>> children = new HashMap<>();
        tree.values().forEach(o -> {
            if (o.getParentId() != null) {
                children.computeIfAbsent(o.getParentId(), k -> new ArrayList<>()).add(o.getId());
            }
        });
        Set<Long> result = new HashSet<>();
        List<Long> stack = new ArrayList<>(children.getOrDefault(id, List.of()));
        while (!stack.isEmpty()) {
            Long cur = stack.remove(stack.size() - 1);
            if (result.add(cur)) {
                stack.addAll(children.getOrDefault(cur, List.of()));
            }
        }
        return result;
    }

    /** 由扁平列表构建树。上级不在列表中的节点（例如上级已停用而只查询启用节点时）作为根节点返回。 */
    static List<OrgTreeNode> buildTree(List<SysOrgUnit> all) {
        Map<Long, SysOrgUnit> byId = new HashMap<>();
        all.forEach(o -> byId.put(o.getId(), o));
        Map<Long, List<SysOrgUnit>> children = new HashMap<>();
        List<SysOrgUnit> roots = new ArrayList<>();
        for (SysOrgUnit o : all) {
            if (o.getParentId() == null || !byId.containsKey(o.getParentId())) {
                roots.add(o);
            } else {
                children.computeIfAbsent(o.getParentId(), k -> new ArrayList<>()).add(o);
            }
        }
        return toNodes(roots, children, 0);
    }

    private static final Comparator<SysOrgUnit> ORDER = Comparator
            .comparing((SysOrgUnit o) -> o.getSortOrder() == null ? 0 : o.getSortOrder())
            .thenComparing(SysOrgUnit::getId);

    private static List<OrgTreeNode> toNodes(List<SysOrgUnit> list, Map<Long, List<SysOrgUnit>> children, int depth) {
        if (depth > SysOrgUnitMapper.MAX_DEPTH) {
            return List.of();
        }
        return list.stream().sorted(ORDER)
                .map(o -> new OrgTreeNode(o.getId(), o.getParentId(), o.getCode(), o.getName(), o.getType(),
                        o.getStatus(), o.getSortOrder() == null ? 0 : o.getSortOrder(),
                        toNodes(children.getOrDefault(o.getId(), List.of()), children, depth + 1)))
                .toList();
    }

    private void audit(String action, Long orgId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("ORG_UNIT", orgId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
