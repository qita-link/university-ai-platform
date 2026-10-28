package com.campus.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.course.entity.Course;
import com.campus.platform.course.entity.CourseStatus;
import com.campus.platform.course.service.CourseAccessService;
import com.campus.platform.course.service.CourseMembershipService;
import com.campus.platform.course.service.CourseService;
import com.campus.platform.knowledge.dto.SpaceCreateRequest;
import com.campus.platform.knowledge.dto.SpaceMemberRequest;
import com.campus.platform.knowledge.dto.SpaceMemberView;
import com.campus.platform.knowledge.dto.SpaceQuery;
import com.campus.platform.knowledge.dto.SpaceUpdateRequest;
import com.campus.platform.knowledge.dto.SpaceView;
import com.campus.platform.knowledge.entity.KbCategory;
import com.campus.platform.knowledge.entity.KbSpace;
import com.campus.platform.knowledge.entity.KbSpaceMember;
import com.campus.platform.knowledge.entity.MemberRole;
import com.campus.platform.knowledge.entity.SpaceStatus;
import com.campus.platform.knowledge.entity.SpaceVisibility;
import com.campus.platform.knowledge.mapper.KbCategoryMapper;
import com.campus.platform.knowledge.mapper.KbSpaceMapper;
import com.campus.platform.knowledge.mapper.KbSpaceMemberMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.datascope.DataScopeService;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import com.campus.platform.user.service.OrgUnitService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 知识库空间、成员与可见范围。
 * <p>
 * 组织范围复用 {@link DataScopeService}；公开空间只允许拥有全局管理权限的账号创建，
 * 教师可在自己可管理的组织范围内创建 ORG/PRIVATE 空间。
 */
@Service
public class KnowledgeSpaceService {

    static final String MODULE = "knowledge";

    private final KbSpaceMapper spaceMapper;
    private final KbCategoryMapper categoryMapper;
    private final KbSpaceMemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final SysOrgUnitMapper orgMapper;
    private final OrgUnitService orgUnitService;
    private final KnowledgeAccessService accessService;
    private final DataScopeService dataScopeService;
    private final CourseService courseService;
    private final CourseAccessService courseAccessService;
    private final CourseMembershipService courseMembershipService;
    private final AuditService auditService;
    private final Clock clock;

    public KnowledgeSpaceService(KbSpaceMapper spaceMapper, KbCategoryMapper categoryMapper,
                                 KbSpaceMemberMapper memberMapper, SysUserMapper userMapper,
                                 SysOrgUnitMapper orgMapper, OrgUnitService orgUnitService,
                                 KnowledgeAccessService accessService, DataScopeService dataScopeService,
                                 CourseService courseService, CourseAccessService courseAccessService,
                                 CourseMembershipService courseMembershipService,
                                 AuditService auditService, Clock clock) {
        this.spaceMapper = spaceMapper;
        this.categoryMapper = categoryMapper;
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.orgUnitService = orgUnitService;
        this.accessService = accessService;
        this.dataScopeService = dataScopeService;
        this.courseService = courseService;
        this.courseAccessService = courseAccessService;
        this.courseMembershipService = courseMembershipService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public PageResult<SpaceView> list(AuthenticatedUser user, SpaceQuery q) {
        Permissions.require(user, Permissions.KB_SPACE_READ);
        LambdaQueryWrapper<KbSpace> w = new LambdaQueryWrapper<>();
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            w.like(KbSpace::getName, escapeLike(q.getKeyword().trim()));
        }
        w.eq(q.getCategoryId() != null, KbSpace::getCategoryId, q.getCategoryId())
                .eq(q.getVisibility() != null, KbSpace::getVisibility, q.getVisibility());

        if (hasGlobalManage(user)) {
            if (q.isIncludeArchived()) {
                w.ne(KbSpace::getStatus, SpaceStatus.DELETED);
            } else {
                w.eq(KbSpace::getStatus, SpaceStatus.ACTIVE);
            }
        } else {
            w.eq(KbSpace::getStatus, SpaceStatus.ACTIVE);
            applyReadableFilter(user, w);
        }
        w.orderByDesc(KbSpace::getId);

        IPage<KbSpace> page = spaceMapper.selectPage(q.toPage(), w);
        List<KbSpace> spaces = page.getRecords();
        Map<Long, String> categories = namesOfCategories(spaces.stream().map(KbSpace::getCategoryId).toList());
        Map<Long, String> owners = namesOfUsers(spaces.stream().map(KbSpace::getOwnerUserId).toList());
        Map<Long, String> orgs = namesOfOrgs(spaces.stream().map(KbSpace::getScopeOrgUnitId).toList());
        Map<Long, String> courses = namesOfCourses(spaces.stream().map(KbSpace::getScopeCourseId).toList());
        Map<Long, Long> memberCounts = memberCounts(spaces.stream().map(KbSpace::getId).toList());
        return PageResult.of(page, s -> view(user, s, categories, orgs, courses, owners, memberCounts));
    }

    public SpaceView get(AuthenticatedUser user, long id) {
        KbSpace space = accessService.requireReadable(user, id);
        return view(user, space, namesOfCategories(List.of(space.getCategoryId() == null ? -1 : space.getCategoryId())),
                namesOfOrgs(List.of(space.getScopeOrgUnitId() == null ? -1 : space.getScopeOrgUnitId())),
                namesOfCourses(List.of(space.getScopeCourseId() == null ? -1 : space.getScopeCourseId())),
                namesOfUsers(List.of(space.getOwnerUserId())),
                memberCounts(List.of(space.getId())));
    }

    @Transactional
    public SpaceView create(AuthenticatedUser user, SpaceCreateRequest req) {
        if (!user.hasPermission(Permissions.KB_SPACE_CREATE)
                && !user.hasPermission(Permissions.KB_SPACE_MANAGE_ALL)) {
            throw BusinessException.forbidden();
        }
        SpaceVisibility visibility = parseVisibility(req.visibility());
        validateVisibility(user, visibility, req.scopeOrgUnitId(), req.scopeCourseId());
        if (req.categoryId() != null) {
            requireCategory(req.categoryId());
        }

        KbSpace space = new KbSpace();
        space.setCategoryId(req.categoryId());
        space.setName(req.name().trim());
        space.setDescription(blankToNull(req.description()));
        space.setVisibility(visibility);
        space.setScopeOrgUnitId(visibility == SpaceVisibility.ORG ? req.scopeOrgUnitId() : null);
        space.setScopeCourseId(visibility == SpaceVisibility.COURSE ? req.scopeCourseId() : null);
        space.setOwnerUserId(user.userId());
        space.setStatus(SpaceStatus.ACTIVE);
        space.setVersion(0);
        spaceMapper.insert(space);

        KbSpaceMember owner = new KbSpaceMember();
        owner.setSpaceId(space.getId());
        owner.setUserId(user.userId());
        owner.setMemberRole(MemberRole.MANAGER);
        owner.setGrantedBy(user.userId());
        memberMapper.insert(owner);

        audit("KB_SPACE_CREATE", space.getId(), "name", space.getName(),
                "visibility", visibility, "ownerUserId", user.userId(),
                "scopeCourseId", space.getScopeCourseId());
        return view(user, space, namesOfCategories(List.of(space.getCategoryId() == null ? -1 : space.getCategoryId())),
                namesOfOrgs(List.of(space.getScopeOrgUnitId() == null ? -1 : space.getScopeOrgUnitId())),
                namesOfCourses(List.of(space.getScopeCourseId() == null ? -1 : space.getScopeCourseId())),
                namesOfUsers(List.of(space.getOwnerUserId())), memberCounts(List.of(space.getId())));
    }

    @Transactional
    public SpaceView update(AuthenticatedUser user, long id, SpaceUpdateRequest req) {
        KbSpace space = accessService.requireManageable(user, id);
        SpaceVisibility visibility = parseVisibility(req.visibility());
        validateVisibility(user, visibility, req.scopeOrgUnitId(), req.scopeCourseId());
        if (req.categoryId() != null) {
            requireCategory(req.categoryId());
        }
        int updated = spaceMapper.update(null, new LambdaUpdateWrapper<KbSpace>()
                .eq(KbSpace::getId, id)
                .eq(KbSpace::getVersion, req.version())
                .set(KbSpace::getCategoryId, req.categoryId())
                .set(KbSpace::getName, req.name().trim())
                .set(KbSpace::getDescription, blankToNull(req.description()))
                .set(KbSpace::getVisibility, visibility)
                .set(KbSpace::getScopeOrgUnitId, visibility == SpaceVisibility.ORG ? req.scopeOrgUnitId() : null)
                .set(KbSpace::getScopeCourseId, visibility == SpaceVisibility.COURSE ? req.scopeCourseId() : null)
                .set(KbSpace::getUpdatedBy, user.userId())
                .set(KbSpace::getUpdatedAt, LocalDateTime.now(clock))
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw BusinessException.conflict("知识库已被其他人修改，请刷新后重试");
        }
        audit("KB_SPACE_UPDATE", id, "name", req.name(), "visibility", visibility);
        return get(user, id);
    }

    @Transactional
    public void delete(AuthenticatedUser user, long id) {
        KbSpace space = accessService.requireManageable(user, id);
        int updated = spaceMapper.update(null, new LambdaUpdateWrapper<KbSpace>()
                .eq(KbSpace::getId, id)
                .eq(KbSpace::getVersion, space.getVersion())
                .set(KbSpace::getStatus, SpaceStatus.DELETED)
                .set(KbSpace::getUpdatedBy, user.userId())
                .set(KbSpace::getUpdatedAt, LocalDateTime.now(clock))
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw BusinessException.conflict("知识库已被其他人修改，请刷新后重试");
        }
        audit("KB_SPACE_DELETE", id, "name", space.getName());
    }

    public List<SpaceMemberView> listMembers(AuthenticatedUser user, long id) {
        accessService.requireManageable(user, id);
        List<KbSpaceMember> members = memberMapper.selectList(new LambdaQueryWrapper<KbSpaceMember>()
                .eq(KbSpaceMember::getSpaceId, id).orderByAsc(KbSpaceMember::getId));
        Map<Long, SysUser> users = usersOf(members.stream().map(KbSpaceMember::getUserId).toList());
        return members.stream().map(m -> {
            SysUser u = users.get(m.getUserId());
            return new SpaceMemberView(m.getUserId(), u == null ? null : u.getUsername(),
                    u == null ? null : u.getDisplayName(), m.getMemberRole(), m.getGrantedBy(), m.getCreatedAt());
        }).toList();
    }

    @Transactional
    public SpaceMemberView addMember(AuthenticatedUser user, long id, SpaceMemberRequest req) {
        KbSpace space = accessService.requireManageable(user, id);
        if (Objects.equals(space.getOwnerUserId(), req.userId())) {
            throw BusinessException.conflict("所有者已经拥有管理权限");
        }
        SysUser target = requireActiveUser(req.userId());
        KbSpaceMember existing = accessService.findMember(id, req.userId());
        if (existing == null) {
            KbSpaceMember member = new KbSpaceMember();
            member.setSpaceId(id);
            member.setUserId(target.getId());
            member.setMemberRole(req.memberRole());
            member.setGrantedBy(user.userId());
            memberMapper.insert(member);
            audit("KB_SPACE_MEMBER_UPSERT", id, "userId", req.userId(), "role", req.memberRole());
            return new SpaceMemberView(target.getId(), target.getUsername(), target.getDisplayName(),
                    req.memberRole(), user.userId(), member.getCreatedAt());
        }
        memberMapper.update(null, new LambdaUpdateWrapper<KbSpaceMember>()
                .eq(KbSpaceMember::getId, existing.getId())
                .set(KbSpaceMember::getMemberRole, req.memberRole())
                .set(KbSpaceMember::getGrantedBy, user.userId()));
        audit("KB_SPACE_MEMBER_UPSERT", id, "userId", req.userId(), "role", req.memberRole());
        return new SpaceMemberView(target.getId(), target.getUsername(), target.getDisplayName(),
                req.memberRole(), user.userId(), existing.getCreatedAt());
    }

    @Transactional
    public SpaceMemberView updateMember(AuthenticatedUser user, long id, long userId, MemberRole role) {
        KbSpace space = accessService.requireManageable(user, id);
        if (Objects.equals(space.getOwnerUserId(), userId)) {
            throw BusinessException.conflict("不能修改所有者的角色");
        }
        KbSpaceMember member = accessService.findMember(id, userId);
        if (member == null) {
            throw BusinessException.notFound("成员");
        }
        memberMapper.update(null, new LambdaUpdateWrapper<KbSpaceMember>()
                .eq(KbSpaceMember::getId, member.getId())
                .set(KbSpaceMember::getMemberRole, role)
                .set(KbSpaceMember::getGrantedBy, user.userId()));
        SysUser target = userMapper.selectById(userId);
        audit("KB_SPACE_MEMBER_UPDATE", id, "userId", userId, "role", role);
        return new SpaceMemberView(userId, target == null ? null : target.getUsername(),
                target == null ? null : target.getDisplayName(), role, user.userId(), member.getCreatedAt());
    }

    @Transactional
    public void removeMember(AuthenticatedUser user, long id, long userId) {
        KbSpace space = accessService.requireManageable(user, id);
        if (Objects.equals(space.getOwnerUserId(), userId)) {
            throw BusinessException.conflict("不能移除知识库所有者");
        }
        KbSpaceMember member = accessService.findMember(id, userId);
        if (member == null) {
            throw BusinessException.notFound("成员");
        }
        memberMapper.deleteById(member.getId());
        audit("KB_SPACE_MEMBER_REMOVE", id, "userId", userId);
    }

    public KbSpace requireSpace(long id) {
        return accessService.requireSpace(id);
    }

    /** 当前用户可读取的活动空间 ID，供检索先做权限过滤。 */
    public List<Long> accessibleSpaceIds(AuthenticatedUser user) {
        Permissions.require(user, Permissions.KB_SPACE_READ);
        LambdaQueryWrapper<KbSpace> w = new LambdaQueryWrapper<KbSpace>()
                .select(KbSpace::getId)
                .eq(KbSpace::getStatus, SpaceStatus.ACTIVE);
        if (!hasGlobalManage(user)) {
            applyReadableFilter(user, w);
        }
        return spaceMapper.selectList(w).stream().map(KbSpace::getId).toList();
    }

    private void applyReadableFilter(AuthenticatedUser user, LambdaQueryWrapper<KbSpace> w) {
        Set<Long> orgIds = accessService.userOrgScope(user);
        Set<Long> courseIds = courseMembershipService.memberCourseIds(user.userId());
        w.and(x -> {
            x.eq(KbSpace::getOwnerUserId, user.userId())
                    .or().eq(KbSpace::getVisibility, SpaceVisibility.PUBLIC)
                    .or().exists("SELECT 1 FROM kb_space_member m WHERE m.space_id = kb_space.id AND m.user_id = {0}",
                            user.userId());
            if (!orgIds.isEmpty()) {
                x.or().nested(y -> y.eq(KbSpace::getVisibility, SpaceVisibility.ORG)
                        .in(KbSpace::getScopeOrgUnitId, orgIds));
            }
            if (!courseIds.isEmpty()) {
                x.or().nested(y -> y.eq(KbSpace::getVisibility, SpaceVisibility.COURSE)
                        .in(KbSpace::getScopeCourseId, courseIds));
            }
        });
    }

    private SpaceView view(AuthenticatedUser user, KbSpace space, Map<Long, String> categories,
                           Map<Long, String> orgs, Map<Long, String> courses, Map<Long, String> owners,
                           Map<Long, Long> memberCounts) {
        return new SpaceView(
                space.getId(), space.getCategoryId(), categories.get(space.getCategoryId()),
                space.getName(), space.getDescription(), space.getVisibility(), space.getScopeOrgUnitId(),
                orgs.get(space.getScopeOrgUnitId()), space.getScopeCourseId(),
                courses.get(space.getScopeCourseId()), space.getOwnerUserId(), owners.get(space.getOwnerUserId()),
                space.getStatus(), memberCounts.getOrDefault(space.getId(), 0L),
                accessService.accessLevel(user, space).name(), space.getVersion() == null ? 0 : space.getVersion(),
                space.getCreatedAt(), space.getUpdatedAt());
    }

    private Map<Long, String> namesOfCategories(Collection<Long> ids) {
        Set<Long> clean = clean(ids);
        Map<Long, String> result = new HashMap<>();
        if (!clean.isEmpty()) {
            categoryMapper.selectBatchIds(clean).forEach(c -> result.put(c.getId(), c.getName()));
        }
        return result;
    }

    private Map<Long, String> namesOfUsers(Collection<Long> ids) {
        Set<Long> clean = clean(ids);
        Map<Long, String> result = new HashMap<>();
        if (!clean.isEmpty()) {
            userMapper.selectBatchIds(clean).forEach(u -> result.put(u.getId(), u.getDisplayName()));
        }
        return result;
    }

    private Map<Long, String> namesOfOrgs(Collection<Long> ids) {
        Set<Long> clean = clean(ids);
        Map<Long, String> result = new HashMap<>();
        if (!clean.isEmpty()) {
            orgMapper.selectBatchIds(clean).forEach(o -> result.put(o.getId(), o.getName()));
        }
        return result;
    }

    private Map<Long, String> namesOfCourses(Collection<Long> ids) {
        Set<Long> clean = clean(ids);
        return clean.isEmpty() ? new HashMap<>() : courseService.courseNamesOf(clean);
    }

    private Map<Long, Long> memberCounts(Collection<Long> spaceIds) {
        Map<Long, Long> result = new HashMap<>();
        for (Long id : clean(spaceIds)) {
            result.put(id, memberMapper.selectCount(new LambdaQueryWrapper<KbSpaceMember>()
                    .eq(KbSpaceMember::getSpaceId, id)));
        }
        return result;
    }

    private Map<Long, SysUser> usersOf(Collection<Long> ids) {
        Set<Long> clean = clean(ids);
        Map<Long, SysUser> result = new HashMap<>();
        if (!clean.isEmpty()) {
            userMapper.selectBatchIds(clean).forEach(u -> result.put(u.getId(), u));
        }
        return result;
    }

    private static Set<Long> clean(Collection<Long> ids) {
        Set<Long> set = new HashSet<>();
        ids.forEach(id -> {
            if (id != null && id > 0) {
                set.add(id);
            }
        });
        return set;
    }

    private KbCategory requireCategory(long id) {
        KbCategory c = categoryMapper.selectById(id);
        if (c == null) {
            throw BusinessException.notFound("分类");
        }
        return c;
    }

    private SysUser requireActiveUser(long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null || u.getStatus() != UserStatus.ACTIVE) {
            throw BusinessException.notFound("用户");
        }
        return u;
    }

    private void validateVisibility(AuthenticatedUser user, SpaceVisibility visibility, Long scopeOrgUnitId,
                                    Long scopeCourseId) {
        if (visibility == SpaceVisibility.COURSE) {
            if (scopeCourseId == null) {
                throw new BusinessException(ErrorCode.KB_SPACE_INVALID_VISIBILITY,
                        "课程可见的知识库必须指定课程");
            }
            Course course = courseService.requireCourse(scopeCourseId);
            if (course.getStatus() == CourseStatus.CANCELLED) {
                throw new BusinessException(ErrorCode.KB_SPACE_INVALID_VISIBILITY, "课程已停开，不能绑定知识库");
            }
            if (!courseAccessService.isAdmin(user) && !courseAccessService.isTeacherOf(user, scopeCourseId)) {
                throw BusinessException.forbidden("只有该课程的任课教师或管理员可以创建课程知识库");
            }
            return;
        }
        if (visibility == SpaceVisibility.PUBLIC && !hasGlobalManage(user)) {
            throw BusinessException.forbidden("只有管理员可以创建公开知识库");
        }
        if (visibility == SpaceVisibility.ORG) {
            if (scopeOrgUnitId == null) {
                throw new BusinessException(ErrorCode.KB_SPACE_ORG_REQUIRED);
            }
            orgUnitService.requireActive(scopeOrgUnitId);
            dataScopeService.requireManageable(user, scopeOrgUnitId, Permissions.KB_SPACE_MANAGE_ALL);
        }
    }

    private static SpaceVisibility parseVisibility(String raw) {
        try {
            return SpaceVisibility.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.KB_SPACE_INVALID_VISIBILITY);
        }
    }

    private static boolean hasGlobalManage(AuthenticatedUser user) {
        return user.hasPermission(Permissions.KB_SPACE_MANAGE_ALL);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("KB_SPACE", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
