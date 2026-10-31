package com.campus.platform.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageQuery;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.forum.entity.ForumCategory;
import com.campus.platform.forum.entity.ForumPost;
import com.campus.platform.forum.entity.ForumPostStatus;
import com.campus.platform.forum.mapper.ForumCategoryMapper;
import com.campus.platform.forum.mapper.ForumPostMapper;
import com.campus.platform.profile.dto.ProfilePostView;
import com.campus.platform.profile.dto.ProfileView;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserAvatar;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import com.campus.platform.user.mapper.UserAvatarMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 个人资料：本人信息、头像、我发过的帖子。
 * <p>
 * 头像存在独立的 user_avatar 表，上传/删除都会让 sys_user.avatar_version 自增，
 * 前端把版本拼进图片 URL，换头像后不会读到旧缓存。
 */
@Slf4j
@Service
public class ProfileService {

    static final String MODULE = "profile";

    /** 头像上限：1.5MB，足够手机直出的头像，也避免把 BLOB 撑大 */
    static final int MAX_AVATAR_BYTES = 1_572_864;
    static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private final SysUserMapper userMapper;
    private final SysOrgUnitMapper orgUnitMapper;
    private final UserAvatarMapper avatarMapper;
    private final ForumPostMapper postMapper;
    private final ForumCategoryMapper categoryMapper;
    private final AuditService auditService;
    private final Clock clock;

    public ProfileService(SysUserMapper userMapper, SysOrgUnitMapper orgUnitMapper,
                          UserAvatarMapper avatarMapper, ForumPostMapper postMapper,
                          ForumCategoryMapper categoryMapper, AuditService auditService, Clock clock) {
        this.userMapper = userMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.avatarMapper = avatarMapper;
        this.postMapper = postMapper;
        this.categoryMapper = categoryMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** 本人的资料卡：身份、学号/工号、姓名、组织、角色。 */
    public ProfileView profile(AuthenticatedUser user) {
        SysUser entity = userMapper.selectById(user.userId());
        if (entity == null) {
            throw BusinessException.notFound("用户");
        }
        String orgName = null;
        if (entity.getOrgUnitId() != null) {
            SysOrgUnit org = orgUnitMapper.selectById(entity.getOrgUnitId());
            orgName = org == null ? null : org.getName();
        }
        return new ProfileView(entity.getId(), entity.getUsername(), entity.getDisplayName(),
                entity.getEmail(), entity.getPhone(), entity.getOrgUnitId(), orgName,
                new TreeSet<>(userMapper.selectRoleCodes(entity.getId())),
                entity.getAvatarVersion() == null ? 0 : entity.getAvatarVersion(),
                entity.getLastLoginAt(), entity.getCreatedAt());
    }

    /** 头像版本：登录/刷新当前用户时一起返回，前端据此拼接头像地址。 */
    public int avatarVersion(long userId) {
        SysUser user = userMapper.selectById(userId);
        return user == null || user.getAvatarVersion() == null ? 0 : user.getAvatarVersion();
    }

    /**
     * 上传头像：校验类型与大小（同时看 Content-Type 与文件头），覆盖旧图并让版本自增。
     */
    @Transactional
    public ProfileView uploadAvatar(AuthenticatedUser user, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("请选择一张图片");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw BusinessException.badRequest("头像不能超过 1.5MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw BusinessException.badRequest("只支持 PNG、JPEG、WebP 图片");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BusinessException.badRequest("读取图片失败，请重试");
        }
        if (!looksLikeImage(bytes)) {
            throw BusinessException.badRequest("文件内容不是有效的图片");
        }

        UserAvatar avatar = avatarMapper.selectById(user.userId());
        boolean exists = avatar != null;
        if (!exists) {
            avatar = new UserAvatar();
            avatar.setUserId(user.userId());
        }
        avatar.setContentType(contentType.toLowerCase());
        avatar.setSizeBytes(bytes.length);
        avatar.setBytes(bytes);
        if (exists) {
            avatarMapper.updateById(avatar);
        } else {
            avatarMapper.insert(avatar);
        }
        bumpVersion(user.userId());
        auditService.record(AuditEvent.builder(MODULE, "PROFILE_AVATAR_UPLOAD")
                .target("USER", user.userId())
                .detail("contentType", contentType.toLowerCase())
                .detail("sizeBytes", bytes.length)
                .build());
        return profile(user);
    }

    /** 删除头像：回到首字头像。 */
    @Transactional
    public ProfileView removeAvatar(AuthenticatedUser user) {
        if (avatarMapper.selectById(user.userId()) == null) {
            return profile(user);
        }
        avatarMapper.deleteById(user.userId());
        bumpVersion(user.userId());
        auditService.record(AuditEvent.builder(MODULE, "PROFILE_AVATAR_DELETE")
                .target("USER", user.userId())
                .build());
        return profile(user);
    }

    /** 读取头像字节：登录用户可看，用来渲染侧栏与帖子作者。 */
    public AvatarFile avatar(long userId) {
        UserAvatar avatar = avatarMapper.selectById(userId);
        if (avatar == null || avatar.getBytes() == null || avatar.getBytes().length == 0) {
            throw BusinessException.notFound("头像");
        }
        return new AvatarFile(avatar.getContentType(), avatar.getBytes());
    }

    /** 我发过的帖子：含被隐藏的，不含已删除；版主隐藏的内容作者本人仍能看到原因入口。 */
    public PageResult<ProfilePostView> myPosts(AuthenticatedUser user, PageQuery query) {
        Page<ForumPost> pageRequest = query.toPage();
        IPage<ForumPost> page = postMapper.selectPage(pageRequest, new LambdaQueryWrapper<ForumPost>()
                .eq(ForumPost::getAuthorId, user.userId())
                .ne(ForumPost::getStatus, ForumPostStatus.DELETED)
                .orderByDesc(ForumPost::getId));
        List<ForumPost> rows = page.getRecords();
        Map<Long, String> categoryNames = categoryNames(rows.stream()
                .map(ForumPost::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet()));
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, String> editorNames = editorNames(rows);
        Map<Long, String> editorRoles = editorRoles(rows);
        List<ProfilePostView> items = rows.stream()
                .map(p -> {
                    // version 只在内容被编辑时自增（评论、置顶不动它），用它判断「改过没有」最可靠
                    boolean edited = p.getVersion() != null && p.getVersion() > 0;
                    Long editorId = p.getUpdatedBy() == null ? p.getAuthorId() : p.getUpdatedBy();
                    return new ProfilePostView(p.getId(), p.getTitle(),
                            categoryNames.get(p.getCategoryId()), p.getStatus(),
                            effectivePinned(p, now), p.getCommentCount() == null ? 0 : p.getCommentCount(),
                            p.getLastActivityAt(), p.getCreatedAt(),
                            edited ? p.getUpdatedAt() : null,
                            edited ? editorNames.get(editorId) : null,
                            edited ? editorRoles.get(editorId) : null);
                })
                .toList();
        return new PageResult<>(items, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /** 编辑者姓名：帖子被谁改过（作者自己改就是作者）。 */
    private Map<Long, String> editorNames(List<ForumPost> posts) {
        Set<Long> ids = posts.stream()
                .filter(p -> p.getVersion() != null && p.getVersion() > 0)
                .map(p -> p.getUpdatedBy() == null ? p.getAuthorId() : p.getUpdatedBy())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (SysUser u : userMapper.selectBatchIds(ids)) {
            names.put(u.getId(), u.getDisplayName());
        }
        return names;
    }

    /** 编辑者的角色：多角色时按 管理员 > 教师 > 学生会 > 学生 取一个用于展示。 */
    private Map<Long, String> editorRoles(List<ForumPost> posts) {
        Set<Long> ids = posts.stream()
                .filter(p -> p.getVersion() != null && p.getVersion() > 0)
                .map(p -> p.getUpdatedBy() == null ? p.getAuthorId() : p.getUpdatedBy())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> roles = new HashMap<>();
        if (ids.isEmpty()) {
            return roles;
        }
        Map<Long, List<String>> byUser = new HashMap<>();
        for (SysUserMapper.UserRoleCode row : userMapper.selectRoleCodesOfUsers(ids)) {
            byUser.computeIfAbsent(row.userId(), k -> new ArrayList<>()).add(row.roleCode());
        }
        byUser.forEach((userId, codes) -> roles.put(userId, primaryRole(codes)));
        return roles;
    }

    private static String primaryRole(List<String> codes) {
        for (String preferred : List.of("ADMIN", "TEACHER", "STUDENT_UNION", "STUDENT")) {
            if (codes.contains(preferred)) {
                return preferred;
            }
        }
        return codes.isEmpty() ? null : codes.get(0);
    }

    private Map<Long, String> categoryNames(Set<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (ForumCategory c : categoryMapper.selectBatchIds(ids)) {
            names.put(c.getId(), c.getName());
        }
        return names;
    }

    private static boolean effectivePinned(ForumPost post, LocalDateTime now) {
        return Boolean.TRUE.equals(post.getPinned())
                && (post.getPinnedUntil() == null || post.getPinnedUntil().isAfter(now));
    }

    /** 版本自增：换头像后前端拿到的 URL 变化，浏览器不会继续用旧缓存。 */
    private void bumpVersion(long userId) {
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .setSql("avatar_version = avatar_version + 1"));
    }

    /**
     * 只看文件头：PNG 的 89 50 4E 47、JPEG 的 FF D8 FF、WebP 的 RIFF....WEBP。
     * 防止把 HTML/脚本改名成 .png 上传后又被浏览器当图片解析。
     */
    static boolean looksLikeImage(byte[] b) {
        if (b == null || b.length < 12) {
            return false;
        }
        boolean png = (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean jpeg = (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
        boolean webp = b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
        return png || jpeg || webp;
    }

    /** 头像内容：Controller 直接写进响应体。 */
    public record AvatarFile(String contentType, byte[] bytes) {
    }
}
