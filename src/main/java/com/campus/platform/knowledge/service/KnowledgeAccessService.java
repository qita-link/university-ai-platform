package com.campus.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.course.service.CourseMembershipService;
import com.campus.platform.knowledge.entity.AccessLevel;
import com.campus.platform.knowledge.entity.KbSpace;
import com.campus.platform.knowledge.entity.KbSpaceMember;
import com.campus.platform.knowledge.entity.MemberRole;
import com.campus.platform.knowledge.entity.SpaceStatus;
import com.campus.platform.knowledge.entity.SpaceVisibility;
import com.campus.platform.knowledge.mapper.KbSpaceMapper;
import com.campus.platform.knowledge.mapper.KbSpaceMemberMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.datascope.DataScopeService;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 知识库访问控制。
 * <p>
 * 访问判断集中在这里，Controller、Service 和后续 AI 工具共用同一套规则：
 * <ul>
 *   <li>ADMIN / kb:space:manage_all：不受限；</li>
 *   <li>owner：可管理；</li>
 *   <li>成员：MANAGER 可管理，EDITOR 可写，VIEWER 只读；</li>
 *   <li>PUBLIC：所有登录用户可读；</li>
 *   <li>ORG：用户所属组织在 scope 组织或其下级时可读（DataScopeService）；</li>
 *   <li>COURSE：该课程的任课教师或已选学生可读（阶段六接入）；</li>
 *   <li>PRIVATE：仅 owner 和成员。</li>
 * </ul>
 * 读不到的空间统一返回 404，避免通过 ID 探测私有空间是否存在。
 */
@Service
public class KnowledgeAccessService {

    private final KbSpaceMapper spaceMapper;
    private final KbSpaceMemberMapper memberMapper;
    private final DataScopeService dataScopeService;
    private final CourseMembershipService courseMembershipService;

    public KnowledgeAccessService(KbSpaceMapper spaceMapper, KbSpaceMemberMapper memberMapper,
                                  DataScopeService dataScopeService,
                                  CourseMembershipService courseMembershipService) {
        this.spaceMapper = spaceMapper;
        this.memberMapper = memberMapper;
        this.dataScopeService = dataScopeService;
        this.courseMembershipService = courseMembershipService;
    }

    public KbSpace requireSpace(long spaceId) {
        KbSpace space = spaceMapper.selectById(spaceId);
        if (space == null) {
            throw BusinessException.notFound("知识库");
        }
        return space;
    }

    public KbSpace requireReadable(AuthenticatedUser user, long spaceId) {
        Permissions.require(user, Permissions.KB_SPACE_READ);
        KbSpace space = requireSpace(spaceId);
        if (!canRead(user, space)) {
            throw BusinessException.notFound("知识库");
        }
        return space;
    }

    public KbSpace requireWritable(AuthenticatedUser user, long spaceId) {
        KbSpace space = requireReadable(user, spaceId);
        Permissions.require(user, Permissions.KB_DOCUMENT_WRITE);
        if (!canWrite(user, space)) {
            throw BusinessException.forbidden("没有该知识库的文档写权限");
        }
        return space;
    }

    public KbSpace requireManageable(AuthenticatedUser user, long spaceId) {
        KbSpace space = requireReadable(user, spaceId);
        if (!canManage(user, space)) {
            throw BusinessException.forbidden("没有该知识库的管理权限");
        }
        return space;
    }

    public boolean canRead(AuthenticatedUser user, KbSpace space) {
        if (user == null || space == null) {
            return false;
        }
        if (hasGlobalManage(user)) {
            return true;
        }
        if (space.getStatus() == SpaceStatus.DELETED) {
            return false;
        }
        boolean owner = user.userId() != null && user.userId().equals(space.getOwnerUserId());
        if (space.getStatus() == SpaceStatus.ARCHIVED) {
            return owner;
        }
        if (owner) {
            return true;
        }
        if (findMember(space.getId(), user.userId()) != null) {
            return true;
        }
        if (space.getVisibility() == SpaceVisibility.PUBLIC) {
            return true;
        }
        if (space.getVisibility() == SpaceVisibility.ORG) {
            return space.getScopeOrgUnitId() != null
                    && dataScopeService.isMemberOf(user, space.getScopeOrgUnitId());
        }
        if (space.getVisibility() == SpaceVisibility.COURSE) {
            return space.getScopeCourseId() != null
                    && courseMembershipService.isMember(user.userId(), space.getScopeCourseId());
        }
        return false;
    }

    public boolean canWrite(AuthenticatedUser user, KbSpace space) {
        if (user == null || space == null || space.getStatus() != SpaceStatus.ACTIVE) {
            return false;
        }
        if (!user.hasPermission(Permissions.KB_DOCUMENT_WRITE)) {
            return false;
        }
        if (hasGlobalManage(user)) {
            return true;
        }
        if (user.userId() != null && user.userId().equals(space.getOwnerUserId())) {
            return true;
        }
        KbSpaceMember member = findMember(space.getId(), user.userId());
        return member != null && member.getMemberRole().canWriteDocuments();
    }

    public boolean canManage(AuthenticatedUser user, KbSpace space) {
        if (user == null || space == null) {
            return false;
        }
        if (hasGlobalManage(user)) {
            return true;
        }
        if (user.userId() != null && user.userId().equals(space.getOwnerUserId())) {
            return true;
        }
        KbSpaceMember member = findMember(space.getId(), user.userId());
        return member != null && member.getMemberRole() == MemberRole.MANAGER;
    }

    public AccessLevel accessLevel(AuthenticatedUser user, KbSpace space) {
        if (user == null || space == null) {
            return AccessLevel.NONE;
        }
        if (hasGlobalManage(user)) {
            return AccessLevel.ADMIN;
        }
        if (user.userId() != null && user.userId().equals(space.getOwnerUserId())) {
            return AccessLevel.OWNER;
        }
        KbSpaceMember member = findMember(space.getId(), user.userId());
        if (member != null) {
            return switch (member.getMemberRole()) {
                case MANAGER -> AccessLevel.MANAGER;
                case EDITOR -> AccessLevel.EDITOR;
                case VIEWER -> AccessLevel.VIEWER;
            };
        }
        if (space.getVisibility() == SpaceVisibility.PUBLIC) {
            return AccessLevel.PUBLIC;
        }
        if (space.getVisibility() == SpaceVisibility.ORG
                && space.getScopeOrgUnitId() != null
                && dataScopeService.isMemberOf(user, space.getScopeOrgUnitId())) {
            return AccessLevel.ORG;
        }
        if (space.getVisibility() == SpaceVisibility.COURSE
                && space.getScopeCourseId() != null
                && courseMembershipService.isMember(user.userId(), space.getScopeCourseId())) {
            return AccessLevel.COURSE;
        }
        return AccessLevel.NONE;
    }

    public KbSpaceMember findMember(Long spaceId, Long userId) {
        if (spaceId == null || userId == null) {
            return null;
        }
        return memberMapper.selectOne(new LambdaQueryWrapper<KbSpaceMember>()
                .eq(KbSpaceMember::getSpaceId, spaceId)
                .eq(KbSpaceMember::getUserId, userId));
    }

    public Set<Long> userOrgScope(AuthenticatedUser user) {
        return dataScopeService.membershipOrgIds(user);
    }

    private static boolean hasGlobalManage(AuthenticatedUser user) {
        return user.hasPermission(Permissions.KB_SPACE_MANAGE_ALL);
    }
}
