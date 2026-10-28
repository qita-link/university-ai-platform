package com.campus.platform.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.auth.service.PasswordPolicy;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.security.Roles;
import com.campus.platform.user.datascope.DataScopeService;
import com.campus.platform.user.dto.UserCreateRequest;
import com.campus.platform.user.dto.UserQuery;
import com.campus.platform.user.dto.UserUpdateRequest;
import com.campus.platform.user.dto.UserView;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysRole;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 管理端用户管理。REST 接口与后续 AI 工具共用本服务，因此每个方法都在内部再次校验功能权限。
 * <p>
 * 保护规则：
 * <ol>
 *   <li><b>不能对自己</b>停用 / 锁定、修改角色、重置密码（修改本人密码走 /auth/password）——防止误操作把自己锁在系统外；</li>
 *   <li><b>至少保留一个可用管理员</b>：停用 / 锁定管理员或移除其 ADMIN 角色前，锁定全部可用管理员的用户行后再计数，
 *       并发的互相停用会被串行化，只有一方成功；</li>
 *   <li><b>不能越权管理</b>：操作人必须拥有目标用户的全部权限点才能修改其资料、状态、密码或角色；
 *       分配的角色所含权限点也必须是操作人已有权限点的子集。这样仅有 user:manage 的账号无法接管管理员，
 *       也无法给别人（或通过别人给自己）授予更高权限；</li>
 *   <li><b>令牌吊销</b>：停用 / 锁定、重置密码时 token_version 自增，旧令牌立即失效；
 *       重新启用后旧令牌也不会恢复。角色变更不需要吊销令牌：每个请求都从数据库加载角色，变更即时生效。</li>
 * </ol>
 * 修改资料使用乐观锁（version），与登录计数更新互不影响。
 * <p>
 * 事务隔离级别使用 READ_COMMITTED：InnoDB 默认的 REPEATABLE READ 会在第一条普通 SELECT 时固定快照，
 * 加锁之后的普通读取（目标用户的角色、权限点）可能读到加锁前的旧数据；READ_COMMITTED 下每条语句都读取最新已提交数据。
 */
@Service
public class UserAdminService {

    static final String MODULE = "user";

    private final SysUserMapper userMapper;
    private final SysOrgUnitMapper orgMapper;
    private final RoleService roleService;
    private final OrgUnitService orgUnitService;
    private final DataScopeService dataScopeService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService auditService;
    private final Clock clock;

    public UserAdminService(SysUserMapper userMapper, SysOrgUnitMapper orgMapper, RoleService roleService,
                            OrgUnitService orgUnitService, DataScopeService dataScopeService,
                            PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                            AuditService auditService, Clock clock) {
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.roleService = roleService;
        this.orgUnitService = orgUnitService;
        this.dataScopeService = dataScopeService;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ======================================================================
    // 查询
    // ======================================================================

    public PageResult<UserView> list(AuthenticatedUser operator, UserQuery q) {
        Permissions.require(operator, Permissions.USER_READ);
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<>();
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            String kw = escapeLike(q.getKeyword().trim());
            w.and(x -> x.like(SysUser::getUsername, kw).or().like(SysUser::getDisplayName, kw));
        }
        w.eq(q.getStatus() != null, SysUser::getStatus, q.getStatus());
        if (q.getOrgUnitId() != null) {
            if (q.isIncludeSubOrgs()) {
                Set<Long> orgIds = dataScopeService.orgAndDescendants(q.getOrgUnitId());
                if (orgIds.isEmpty()) {
                    return PageResult.empty(q.getPage(), q.getSize());
                }
                w.in(SysUser::getOrgUnitId, orgIds);
            } else {
                w.eq(SysUser::getOrgUnitId, q.getOrgUnitId());
            }
        }
        if (q.getRoleCode() != null) {
            // {0} 由 MyBatis-Plus 作为预编译参数绑定，不拼接 SQL
            w.exists("SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id "
                    + "WHERE ur.user_id = sys_user.id AND r.code = {0}", q.getRoleCode());
        }
        w.orderByDesc(SysUser::getId);
        IPage<SysUser> page = userMapper.selectPage(q.toPage(), w);
        List<SysUser> users = page.getRecords();
        Map<Long, SortedSet<String>> roles = rolesOf(users.stream().map(SysUser::getId).toList());
        Map<Long, String> orgNames = orgNamesOf(users.stream().map(SysUser::getOrgUnitId).toList());
        return PageResult.of(page, u -> toView(u, roles.getOrDefault(u.getId(), new TreeSet<>()), orgNames));
    }

    public UserView get(AuthenticatedUser operator, long id) {
        Permissions.require(operator, Permissions.USER_READ);
        return view(requireUser(id));
    }

    // ======================================================================
    // 创建与修改
    // ======================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserView create(AuthenticatedUser operator, UserCreateRequest req) {
        Permissions.require(operator, Permissions.USER_MANAGE);
        Permissions.require(operator, Permissions.ROLE_MANAGE);
        String username = req.username().trim();
        List<SysRole> roles = roleService.resolve(req.roleCodes());
        requireGrantable(operator, roles);
        passwordPolicy.validate(req.password(), username);
        orgUnitService.requireActive(req.orgUnitId());
        if (userMapper.exists(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username))) {
            throw BusinessException.conflict("用户名已存在");
        }
        String email = blankToNull(req.email());
        requireEmailUnique(email, null);

        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setDisplayName(req.displayName().trim());
        u.setEmail(email);
        u.setPhone(blankToNull(req.phone()));
        u.setOrgUnitId(req.orgUnitId());
        u.setStatus(UserStatus.ACTIVE);
        u.setTokenVersion(0);
        u.setFailedLoginCount(0);
        u.setPasswordChangedAt(LocalDateTime.now(clock));
        userMapper.insert(u);
        userMapper.insertRoles(u.getId(), roles.stream().map(SysRole::getId).toList());
        audit("USER_CREATE", u.getId(), "username", username, "roles", codes(roles), "orgUnitId", req.orgUnitId());
        return view(requireUser(u.getId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserView update(AuthenticatedUser operator, long id, UserUpdateRequest req) {
        Permissions.require(operator, Permissions.USER_MANAGE);
        SysUser target = requireUser(id);
        requireNotHigherPrivileged(operator, target);
        if (!Objects.equals(req.orgUnitId(), target.getOrgUnitId())) {
            orgUnitService.requireActive(req.orgUnitId());
        }
        String email = blankToNull(req.email());
        requireEmailUnique(email, id);

        int updated = userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .eq(SysUser::getVersion, req.version())
                .set(SysUser::getDisplayName, req.displayName().trim())
                .set(SysUser::getEmail, email)
                .set(SysUser::getPhone, blankToNull(req.phone()))
                .set(SysUser::getOrgUnitId, req.orgUnitId())
                .set(SysUser::getUpdatedBy, operator.userId())
                .set(SysUser::getUpdatedAt, LocalDateTime.now(clock))
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw BusinessException.conflict("用户信息已被其他人修改，请刷新后重试");
        }
        audit("USER_UPDATE", id, "orgUnitId", req.orgUnitId(), "oldOrgUnitId", target.getOrgUnitId());
        return view(requireUser(id));
    }

    // ======================================================================
    // 状态、解锁、密码
    // ======================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserView changeStatus(AuthenticatedUser operator, long id, UserStatus target, String reason) {
        Permissions.require(operator, Permissions.USER_MANAGE);
        if (target != UserStatus.ACTIVE) {
            requireNotSelf(operator, id, "USER_STATUS_CHANGE");
        }
        // 先锁管理员集合，再锁目标行（所有涉及管理员保护的操作使用同一加锁顺序）
        List<Long> activeAdmins = userMapper.lockActiveAdminIds();
        SysUser user = lockUser(id);
        requireNotHigherPrivileged(operator, user);
        if (user.getStatus() == target) {
            return view(user);
        }
        if (target != UserStatus.ACTIVE && activeAdmins.contains(id) && activeAdmins.size() <= 1) {
            auditRuleViolation("USER_STATUS_CHANGE", id, ErrorCode.USER_LAST_ADMIN);
            throw new BusinessException(ErrorCode.USER_LAST_ADMIN);
        }

        LambdaUpdateWrapper<SysUser> w = new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .set(SysUser::getStatus, target)
                .set(SysUser::getUpdatedBy, operator.userId())
                .set(SysUser::getUpdatedAt, LocalDateTime.now(clock))
                .setSql("version = version + 1");
        if (target == UserStatus.ACTIVE) {
            // 启用同时解除连续失败造成的临时锁定
            w.set(SysUser::getFailedLoginCount, 0).set(SysUser::getLockedUntil, null);
        } else {
            w.setSql("token_version = token_version + 1");
        }
        userMapper.update(null, w);
        audit("USER_STATUS_CHANGE", id, "from", user.getStatus(), "to", target, "reason", reason);
        return view(requireUser(id));
    }

    /** 解除连续登录失败造成的临时锁定（不改变账号状态）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserView unlock(AuthenticatedUser operator, long id) {
        Permissions.require(operator, Permissions.USER_MANAGE);
        SysUser user = lockUser(id);
        requireNotHigherPrivileged(operator, user);
        userMapper.updateLoginFailure(id, 0, null);
        audit("USER_UNLOCK", id, "previousFailedCount", user.getFailedLoginCount());
        return view(requireUser(id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void resetPassword(AuthenticatedUser operator, long id, String newPassword) {
        Permissions.require(operator, Permissions.USER_MANAGE);
        requireNotSelf(operator, id, "USER_PASSWORD_RESET");
        SysUser user = lockUser(id);
        requireNotHigherPrivileged(operator, user);
        passwordPolicy.validate(newPassword, user.getUsername());
        userMapper.updatePassword(id, passwordEncoder.encode(newPassword), LocalDateTime.now(clock), null,
                operator.userId());
        audit("USER_PASSWORD_RESET", id);
    }

    // ======================================================================
    // 角色
    // ======================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserView assignRoles(AuthenticatedUser operator, long id, Set<String> roleCodes) {
        Permissions.require(operator, Permissions.ROLE_MANAGE);
        requireNotSelf(operator, id, "USER_ROLES_ASSIGN");
        List<SysRole> roles = roleService.resolve(roleCodes);
        requireGrantable(operator, roles);

        List<Long> activeAdmins = userMapper.lockActiveAdminIds();
        SysUser user = lockUser(id);
        requireNotHigherPrivileged(operator, user);
        Set<String> before = new TreeSet<>(userMapper.selectRoleCodes(id));
        Set<String> after = codes(roles);
        if (before.equals(after)) {
            return view(user);
        }
        boolean removesAdmin = before.contains(Roles.ADMIN) && !after.contains(Roles.ADMIN);
        if (removesAdmin && activeAdmins.contains(id) && activeAdmins.size() <= 1) {
            auditRuleViolation("USER_ROLES_ASSIGN", id, ErrorCode.USER_LAST_ADMIN);
            throw new BusinessException(ErrorCode.USER_LAST_ADMIN);
        }
        userMapper.deleteRoles(id);
        if (!roles.isEmpty()) {
            userMapper.insertRoles(id, roles.stream().map(SysRole::getId).toList());
        }
        audit("USER_ROLES_ASSIGN", id, "from", before, "to", after);
        return view(requireUser(id));
    }

    // ======================================================================
    // 规则
    // ======================================================================

    private void requireNotSelf(AuthenticatedUser operator, long targetId, String action) {
        if (operator.userId() != null && operator.userId() == targetId) {
            auditRuleViolation(action, targetId, ErrorCode.USER_SELF_OPERATION_FORBIDDEN);
            throw new BusinessException(ErrorCode.USER_SELF_OPERATION_FORBIDDEN);
        }
    }

    /** 操作人必须拥有目标用户的全部权限点 */
    private void requireNotHigherPrivileged(AuthenticatedUser operator, SysUser target) {
        List<String> targetPerms = userMapper.selectPermissionCodes(target.getId());
        if (!operator.permissions().containsAll(targetPerms)) {
            auditService.record(AuditEvent.builder(MODULE, "USER_MANAGE").target("USER", target.getId())
                    .denied().detail("reason", "TARGET_HAS_HIGHER_PRIVILEGE").build());
            throw BusinessException.forbidden("不能管理权限高于自己的用户");
        }
    }

    /** 授予的角色所含权限点必须是操作人已有权限点的子集 */
    private void requireGrantable(AuthenticatedUser operator, List<SysRole> roles) {
        Set<String> perms = roleService.permissionsOf(roles);
        if (!operator.permissions().containsAll(perms)) {
            throw BusinessException.forbidden("不能授予超出自身权限的角色");
        }
    }

    private void requireEmailUnique(String email, Long selfId) {
        if (email == null) {
            return;
        }
        boolean taken = userMapper.exists(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email)
                .ne(selfId != null, SysUser::getId, selfId));
        if (taken) {
            throw BusinessException.conflict("邮箱已被其他账号使用");
        }
    }

    // ======================================================================
    // helpers
    // ======================================================================

    private SysUser requireUser(long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw BusinessException.notFound("用户");
        }
        return u;
    }

    private SysUser lockUser(long id) {
        SysUser u = userMapper.selectByIdForUpdate(id);
        if (u == null) {
            throw BusinessException.notFound("用户");
        }
        return u;
    }

    private UserView view(SysUser u) {
        SortedSet<String> roles = new TreeSet<>(userMapper.selectRoleCodes(u.getId()));
        return toView(u, roles, orgNamesOf(u.getOrgUnitId() == null ? List.of() : List.of(u.getOrgUnitId())));
    }

    private UserView toView(SysUser u, SortedSet<String> roles, Map<Long, String> orgNames) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime lockedUntil = u.getLockedUntil() != null && u.getLockedUntil().isAfter(now)
                ? u.getLockedUntil() : null;
        return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.getPhone(),
                u.getOrgUnitId(), u.getOrgUnitId() == null ? null : orgNames.get(u.getOrgUnitId()),
                u.getStatus(), lockedUntil,
                u.getFailedLoginCount() == null ? 0 : u.getFailedLoginCount(), roles,
                u.getLastLoginAt(), u.getPasswordChangedAt(), u.getVersion() == null ? 0 : u.getVersion(),
                u.getCreatedAt(), u.getUpdatedAt());
    }

    private Map<Long, SortedSet<String>> rolesOf(Collection<Long> userIds) {
        Map<Long, SortedSet<String>> map = new HashMap<>();
        if (userIds.isEmpty()) {
            return map;
        }
        userMapper.selectRoleCodesOfUsers(userIds)
                .forEach(r -> map.computeIfAbsent(r.userId(), k -> new TreeSet<>()).add(r.roleCode()));
        return map;
    }

    private Map<Long, String> orgNamesOf(Collection<Long> orgIds) {
        Set<Long> ids = new HashSet<>();
        orgIds.forEach(id -> {
            if (id != null) {
                ids.add(id);
            }
        });
        Map<Long, String> map = new HashMap<>();
        if (!ids.isEmpty()) {
            orgMapper.selectBatchIds(ids).forEach((SysOrgUnit o) -> map.put(o.getId(), o.getName()));
        }
        return map;
    }

    private static Set<String> codes(Collection<SysRole> roles) {
        Set<String> s = new TreeSet<>();
        roles.forEach(r -> s.add(r.getCode()));
        return s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 转义 LIKE 通配符，避免用户输入的 % _ 扩大匹配范围 */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private void audit(String action, Long userId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("USER", userId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }

    private void auditRuleViolation(String action, Long userId, ErrorCode code) {
        auditService.record(AuditEvent.builder(MODULE, action).target("USER", userId).failure(code.name()).build());
    }
}
