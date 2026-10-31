package com.campus.platform.forum.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.common.util.SearchTerms;
import com.campus.platform.forum.dto.ForumPostCreateRequest;
import com.campus.platform.forum.dto.ForumPostDetailView;
import com.campus.platform.forum.dto.ForumPostQuery;
import com.campus.platform.forum.dto.ForumPostStatusRequest;
import com.campus.platform.forum.dto.ForumPostSummaryView;
import com.campus.platform.forum.dto.ForumPostUpdateRequest;
import com.campus.platform.forum.entity.ForumCategory;
import com.campus.platform.forum.entity.ForumPost;
import com.campus.platform.forum.entity.ForumPostStatus;
import com.campus.platform.forum.mapper.ForumPostMapper;
import com.campus.platform.forum.mapper.ForumPostRow;
import com.campus.platform.notification.entity.NotificationCategory;
import com.campus.platform.notification.entity.NotificationLevel;
import com.campus.platform.notification.service.NotificationService;
import com.campus.platform.setting.service.SettingService;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 论坛帖子：发帖、编辑、软删除、列表/关键词检索、版主隐藏/恢复/置顶。
 * <p>
 * Service 方法直接接收 {@link AuthenticatedUser}，阶段七的 AI 工具会复用同一套方法与权限判断。
 * 帖子内容修改使用 version 乐观锁；评论计数/最后活跃时间由评论服务在锁定帖子行后更新。
 */
@Service
public class ForumPostService {

    static final String MODULE = "forum";
    private static final int EXCERPT_CHARS = 160;

    private final ForumPostMapper postMapper;
    private final ForumCategoryService categoryService;
    private final ForumAccessService accessService;
    private final SysUserMapper userMapper;
    private final NotificationService notificationService;
    private final SettingService settingService;
    private final AuditService auditService;
    private final Clock clock;
    private final SensitiveWordService sensitiveWordService;

    public ForumPostService(ForumPostMapper postMapper, ForumCategoryService categoryService,
                            ForumAccessService accessService, SysUserMapper userMapper,
                            NotificationService notificationService, SettingService settingService,
                            AuditService auditService, Clock clock, SensitiveWordService sensitiveWordService) {
        this.postMapper = postMapper;
        this.categoryService = categoryService;
        this.accessService = accessService;
        this.userMapper = userMapper;
        this.notificationService = notificationService;
        this.settingService = settingService;
        this.auditService = auditService;
        this.clock = clock;
        this.sensitiveWordService = sensitiveWordService;
    }

    /**
     * 帖子分页与关键词检索。默认只返回 PUBLISHED；版主可用 status=HIDDEN 查看被隐藏内容。
     * 停用分类下的帖子不返回。
     */
    public PageResult<ForumPostSummaryView> list(AuthenticatedUser user, ForumPostQuery q) {
        requireUser(user);
        ForumPostStatus status = q.getStatus() == null ? ForumPostStatus.PUBLISHED : q.getStatus();
        if (status == ForumPostStatus.DELETED) {
            throw new BusinessException(ErrorCode.FORUM_POST_STATUS_INVALID, "已删除帖子不可查询");
        }
        if (status != ForumPostStatus.PUBLISHED && !accessService.isModerator(user)) {
            throw BusinessException.forbidden("只有版主/管理员可以查看隐藏帖子");
        }

        String query = null;
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            query = SearchTerms.toBooleanQuery(q.getKeyword());
            if (query.isBlank()) {
                return PageResult.empty(q.getPage(), q.getSize());
            }
        }

        Page<ForumPostRow> pageRequest = q.toPage();
        IPage<ForumPostRow> page = postMapper.selectPostPage(pageRequest, query,
                q.getCategoryId(), q.getAuthorId(), status.name());
        Map<Long, String> authorNames = namesOfUsers(page.getRecords().stream()
                .map(ForumPostRow::getAuthorId).toList());
        Map<Long, Integer> avatarVersions = avatarVersionsOf(page.getRecords().stream()
                .map(ForumPostRow::getAuthorId).toList());
        List<ForumPostSummaryView> items = page.getRecords().stream()
                .map(row -> summary(row, authorNames.get(row.getAuthorId()),
                        avatarVersions.getOrDefault(row.getAuthorId(), 0)))
                .toList();
        return new PageResult<>(items, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public ForumPostDetailView get(AuthenticatedUser user, long id) {
        requireUser(user);
        ForumPost post = requirePost(id);
        ForumCategory category = categoryService.require(post.getCategoryId());
        accessService.requireReadable(user, post, category);
        return detail(post, category, nameOf(post.getAuthorId()));
    }

    @Transactional
    public ForumPostDetailView create(AuthenticatedUser user, ForumPostCreateRequest req) {
        Permissions.require(user, Permissions.FORUM_POST_WRITE);
        ForumCategory category = categoryService.requireActive(req.categoryId());
        requireAnnouncePermission(user, category);
        // 敏感词在落库前拦掉：违规内容一旦发出去被截图就很难收回
        sensitiveWordService.check(req.title(), req.content());
        LocalDateTime now = LocalDateTime.now(clock);

        ForumPost post = new ForumPost();
        post.setCategoryId(category.getId());
        post.setAuthorId(user.userId());
        post.setTitle(req.title().trim());
        post.setContent(req.content().trim());
        post.setStatus(ForumPostStatus.PUBLISHED);
        post.setPinned(false);
        post.setCommentCount(0);
        post.setLastActivityAt(now);
        post.setVersion(0);
        postMapper.insert(post);

        audit("FORUM_POST_CREATE", post.getId(), "categoryId", category.getId(),
                "title", post.getTitle(), "announcement", Boolean.TRUE.equals(category.getAnnouncement()));
        if (Boolean.TRUE.equals(category.getAnnouncement())) {
            publishAnnouncement(user, post, category);
        }
        return detail(post, category, user.displayName());
    }

    @Transactional
    public ForumPostDetailView update(AuthenticatedUser user, long id, ForumPostUpdateRequest req) {
        ForumPost post = requirePost(id);
        accessService.requireEditable(user, post);
        ForumCategory category = categoryService.requireActive(req.categoryId());
        if (Boolean.TRUE.equals(category.getAnnouncement()) || isAnnouncementPost(post)) {
            requireAnnouncePermission(user, category);
        }
        sensitiveWordService.check(req.title(), req.content());
        LocalDateTime now = LocalDateTime.now(clock);

        int updated = postMapper.update(null, new LambdaUpdateWrapper<ForumPost>()
                .eq(ForumPost::getId, id)
                .eq(ForumPost::getVersion, req.version())
                .set(ForumPost::getCategoryId, category.getId())
                .set(ForumPost::getTitle, req.title().trim())
                .set(ForumPost::getContent, req.content().trim())
                .set(ForumPost::getUpdatedBy, user.userId())
                .set(ForumPost::getUpdatedAt, now)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw BusinessException.conflict("帖子已被其他人修改，请刷新后重试");
        }
        audit("FORUM_POST_UPDATE", id, "categoryId", category.getId(), "title", req.title().trim());
        return get(user, id);
    }

    /** 软删除：status=DELETED。作者或版主/管理员可操作；已删除帖子后续读取统一 404。 */
    @Transactional
    public void delete(AuthenticatedUser user, long id) {
        ForumPost post = requirePost(id);
        accessService.requireDeletable(user, post);
        LocalDateTime now = LocalDateTime.now(clock);

        int updated = postMapper.update(null, new LambdaUpdateWrapper<ForumPost>()
                .eq(ForumPost::getId, id)
                .ne(ForumPost::getStatus, ForumPostStatus.DELETED)
                .set(ForumPost::getStatus, ForumPostStatus.DELETED)
                .set(ForumPost::getDeletedAt, now)
                .set(ForumPost::getDeletedBy, user.userId())
                .set(ForumPost::getUpdatedBy, user.userId())
                .set(ForumPost::getUpdatedAt, now)
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw BusinessException.notFound("帖子");
        }
        audit("FORUM_POST_DELETE", id, "title", post.getTitle(), "authorId", post.getAuthorId());
        if (!user.userId().equals(post.getAuthorId()) && settingService.getBool("forum.notify.moderate", true)) {
            notificationService.notify(post.getAuthorId(), NotificationCategory.FORUM,
                    NotificationLevel.WARNING, "你的帖子被删除",
                    "《" + post.getTitle() + "》已被管理员删除。如有疑问可联系版主。",
                    "/forum", "FORUM_POST", id);
        }
    }

    /**
     * 版主/管理员隐藏、恢复或置顶帖子。
     * <p>
     * 不修改 version：隐藏/置顶不是内容修改，作者仍可编辑被隐藏的帖子，编辑后保持隐藏，
     * 直到版主恢复。状态只能改为 PUBLISHED/HIDDEN；删除必须走 DELETE 接口。
     */
    @Transactional
    public void moderate(AuthenticatedUser user, long id, ForumPostStatusRequest req) {
        accessService.requireModerator(user);
        ForumPost post = requirePost(id);
        if (req.status() == null && req.pinned() == null) {
            throw BusinessException.badRequest("status 与 pinned 至少提供一个");
        }
        if (req.status() == ForumPostStatus.DELETED) {
            throw new BusinessException(ErrorCode.FORUM_POST_STATUS_INVALID, "不能通过该接口删除帖子，请使用 DELETE");
        }
        ForumPostStatus targetStatus = req.status() == null ? post.getStatus() : req.status();
        LocalDateTime now = LocalDateTime.now(clock);
        boolean currentlyPinned = isPinned(post.getPinned(), post.getPinnedUntil(), now);
        boolean targetPinned = req.pinned() == null ? currentlyPinned : req.pinned();

        // 只有显式传 pinned 时才动到期时间：否则改状态会把已有置顶的期限重置
        LocalDateTime targetPinnedUntil = post.getPinnedUntil();
        if (req.pinned() != null) {
            targetPinnedUntil = targetPinned ? pinDeadline(req.pinnedDays(), now) : null;
        } else if (!currentlyPinned) {
            // 顺手清掉已过期的置顶，避免数据长期停留在「pinned=true 但已失效」
            targetPinnedUntil = null;
        }

        postMapper.update(null, new LambdaUpdateWrapper<ForumPost>()
                .eq(ForumPost::getId, id)
                .set(ForumPost::getStatus, targetStatus)
                .set(ForumPost::getPinned, targetPinned)
                .set(ForumPost::getPinnedUntil, targetPinnedUntil)
                .set(ForumPost::getUpdatedBy, user.userId())
                .set(ForumPost::getUpdatedAt, now));
        audit("FORUM_POST_MODERATE", id, "title", post.getTitle(),
                "statusFrom", post.getStatus(), "statusTo", targetStatus,
                "pinnedFrom", post.getPinned(), "pinnedTo", targetPinned,
                "pinnedUntil", targetPinnedUntil,
                "reason", req.reason());
        notifyModeration(user, post, targetStatus, targetPinned, req.reason());
    }

    /**
     * 置顶到期时间：0 表示长期置顶（返回 null），不传默认 7 天。
     * 上限由 DTO 的 @Max(90) 兜底，避免误传把帖子顶到几年后。
     */
    private LocalDateTime pinDeadline(Integer pinnedDays, LocalDateTime now) {
        if (pinnedDays != null && pinnedDays == 0) {
            return null;
        }
        int days = pinnedDays == null ? 7 : pinnedDays;
        return now.plusDays(days);
    }

    /** 置顶是否仍然有效：无到期时间视为长期置顶。 */
    private static boolean isPinned(Boolean pinned, LocalDateTime until, LocalDateTime now) {
        return Boolean.TRUE.equals(pinned) && (until == null || until.isAfter(now));
    }

    /** 版主的隐藏/恢复/置顶操作要让作者知道，否则作者只会以为帖子「消失」了。 */
    private void notifyModeration(AuthenticatedUser operator, ForumPost post, ForumPostStatus targetStatus,
                                  boolean targetPinned, String reason) {
        if (!settingService.getBool("forum.notify.moderate", true)) {
            return;
        }
        boolean statusChanged = post.getStatus() != targetStatus;
        boolean pinnedChanged = Boolean.TRUE.equals(post.getPinned()) != targetPinned;
        if (!statusChanged && !pinnedChanged) {
            return;
        }
        String suffix = reason == null || reason.isBlank() ? "" : "：管理员说明「" + reason.trim() + "」";
        if (statusChanged) {
            boolean hidden = targetStatus == ForumPostStatus.HIDDEN;
            notificationService.notify(post.getAuthorId(), NotificationCategory.FORUM,
                    hidden ? NotificationLevel.WARNING : NotificationLevel.INFO,
                    hidden ? "你的帖子被隐藏" : "你的帖子已恢复",
                    "《" + post.getTitle() + "》" + (hidden ? "已被版主隐藏，仅你与版主可见" : "已重新公开") + suffix,
                    "/forum/posts/" + post.getId(), "FORUM_POST", post.getId());
            return;
        }
        notificationService.notify(post.getAuthorId(), NotificationCategory.FORUM, NotificationLevel.INFO,
                targetPinned ? "你的帖子被置顶" : "你的帖子已取消置顶",
                "《" + post.getTitle() + "》" + (targetPinned ? "已被置顶" : "已取消置顶") + suffix,
                "/forum/posts/" + post.getId(), "FORUM_POST", post.getId());
    }

    /** 公告分类发帖需要 forum:announce，避免任何学生都能触发全员广播。 */
    private void requireAnnouncePermission(AuthenticatedUser user, ForumCategory category) {
        if (Boolean.TRUE.equals(category.getAnnouncement())) {
            Permissions.require(user, Permissions.FORUM_ANNOUNCE);
        }
    }

    private boolean isAnnouncementPost(ForumPost post) {
        ForumCategory category = categoryService.require(post.getCategoryId());
        return Boolean.TRUE.equals(category.getAnnouncement());
    }

    /**
     * 官方公告：广播全员站内信，并按设置决定是否要求前端弹窗。
     * 广播失败不影响公告本身发布，{@code NotificationService} 内部已吞掉异常并记日志。
     */
    private void publishAnnouncement(AuthenticatedUser user, ForumPost post, ForumCategory category) {
        boolean popup = settingService.getBool("announcement.popup", true);
        String body = excerpt(post.getContent(), 120);
        int sent = notificationService.broadcast(NotificationCategory.ANNOUNCEMENT, NotificationLevel.INFO,
                "【公告】" + post.getTitle(), body, "/forum/posts/" + post.getId(),
                "FORUM_POST", post.getId(), popup, null, user.userId());
        audit("FORUM_ANNOUNCEMENT_PUBLISH", post.getId(), "title", post.getTitle(),
                "categoryId", category.getId(), "popup", popup, "recipients", sent);
    }

    public ForumPost requirePost(long id) {
        ForumPost post = postMapper.selectById(id);
        if (post == null || post.getStatus() == ForumPostStatus.DELETED) {
            throw BusinessException.notFound("帖子");
        }
        return post;
    }

    private ForumPostDetailView detail(ForumPost post, ForumCategory category, String authorName) {
        LocalDateTime now = LocalDateTime.now(clock);
        boolean pinned = isPinned(post.getPinned(), post.getPinnedUntil(), now);
        return new ForumPostDetailView(post.getId(), post.getCategoryId(),
                category == null ? null : category.getName(), post.getAuthorId(), authorName,
                avatarVersionOf(post.getAuthorId()),
                post.getTitle(), post.getContent(), post.getStatus(),
                pinned, pinned ? post.getPinnedUntil() : null,
                value(post.getCommentCount()),
                post.getLastActivityAt(), post.getCreatedAt(), post.getUpdatedAt(), value(post.getVersion()));
    }

    private ForumPostSummaryView summary(ForumPostRow row, String authorName, int avatarVersion) {
        LocalDateTime now = LocalDateTime.now(clock);
        boolean pinned = isPinned(row.getPinned(), row.getPinnedUntil(), now);
        return new ForumPostSummaryView(row.getId(), row.getCategoryId(), row.getCategoryName(),
                row.getAuthorId(), authorName, avatarVersion,
                row.getTitle(), excerpt(row.getContent(), EXCERPT_CHARS),
                row.getStatus(), pinned, pinned ? row.getPinnedUntil() : null,
                value(row.getCommentCount()),
                row.getLastActivityAt(), row.getCreatedAt(), row.getUpdatedAt(), value(row.getVersion()));
    }

    private String nameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = userMapper.selectById(userId);
        return user == null ? null : user.getDisplayName();
    }

    private int avatarVersionOf(Long userId) {
        if (userId == null) {
            return 0;
        }
        return avatarVersionsOf(List.of(userId)).getOrDefault(userId, 0);
    }

    /** 批量取头像版本，列表里一次查完，避免 N+1。 */
    private Map<Long, Integer> avatarVersionsOf(Collection<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(id -> id != null && id > 0).collect(Collectors.toSet());
        Map<Long, Integer> versions = new HashMap<>();
        if (ids.isEmpty()) {
            return versions;
        }
        userMapper.selectAvatarVersions(ids)
                .forEach(row -> versions.put(row.getId(), row.getAvatarVersion() == null ? 0 : row.getAvatarVersion()));
        return versions;
    }

    private Map<Long, String> namesOfUsers(Collection<Long> userIds) {
        Set<Long> ids = new HashSet<>();
        userIds.forEach(id -> {
            if (id != null && id > 0) {
                ids.add(id);
            }
        });
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            userMapper.selectBatchIds(ids).forEach(u -> names.put(u.getId(), u.getDisplayName()));
        }
        return names;
    }

    private static int value(Integer n) {
        return n == null ? 0 : n;
    }

    private static String excerpt(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        String s = content.replaceAll("\\s+", " ").trim();
        int length = s.codePointCount(0, s.length());
        if (length <= maxChars) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxChars)) + "…";
    }

    private static AuthenticatedUser requireUser(AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("FORUM_POST", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
