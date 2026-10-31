package com.campus.platform.forum.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageQuery;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.forum.dto.ForumCommentCreateRequest;
import com.campus.platform.forum.dto.ForumCommentThreadView;
import com.campus.platform.forum.dto.ForumCommentView;
import com.campus.platform.forum.entity.ForumCategory;
import com.campus.platform.forum.entity.ForumCategoryStatus;
import com.campus.platform.forum.entity.ForumComment;
import com.campus.platform.forum.entity.ForumCommentStatus;
import com.campus.platform.forum.entity.ForumPost;
import com.campus.platform.forum.entity.ForumPostStatus;
import com.campus.platform.forum.mapper.ForumCommentMapper;
import com.campus.platform.forum.mapper.ForumPostMapper;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 论坛评论（两级）：顶级评论 + 楼中楼回复。
 * <p>
 * 回复的 rootId 指向顶级评论；回复一条回复时 parentId 指向该回复、rootId 仍指向顶级评论，
 * 因此线程始终只有两层。创建/删除评论先锁定帖子行，串行化 comment_count 与帖子删除，
 * 避免并发下计数丢失或给已删除帖子追加评论。
 */
@Service
public class ForumCommentService {

    static final String MODULE = "forum";

    private final ForumCommentMapper commentMapper;
    private final ForumPostMapper postMapper;
    private final ForumCategoryService categoryService;
    private final ForumAccessService accessService;
    private final SysUserMapper userMapper;
    private final NotificationService notificationService;
    private final SettingService settingService;
    private final AuditService auditService;
    private final Clock clock;
    private final SensitiveWordService sensitiveWordService;

    public ForumCommentService(ForumCommentMapper commentMapper, ForumPostMapper postMapper,
                               ForumCategoryService categoryService, ForumAccessService accessService,
                               SysUserMapper userMapper, NotificationService notificationService,
                               SettingService settingService, AuditService auditService, Clock clock,
                               SensitiveWordService sensitiveWordService) {
        this.commentMapper = commentMapper;
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
     * 评论线程分页：按顶级评论分页，每个线程带上其全部回复。
     * 停用分类/隐藏帖子的可见性与帖子详情保持一致（无权读取返回 404）。
     */
    public PageResult<ForumCommentThreadView> list(AuthenticatedUser user, long postId, PageQuery q) {
        requireUser(user);
        ForumPost post = postMapper.selectById(postId);
        if (post == null) {
            throw BusinessException.notFound("帖子");
        }
        ForumCategory category = categoryService.require(post.getCategoryId());
        accessService.requireReadable(user, post, category);

        Page<ForumComment> pageRequest = q.toPage();
        IPage<ForumComment> roots = commentMapper.selectPage(pageRequest,
                new LambdaQueryWrapper<ForumComment>()
                        .eq(ForumComment::getPostId, postId)
                        .isNull(ForumComment::getParentId)
                        .eq(ForumComment::getStatus, ForumCommentStatus.PUBLISHED)
                        .orderByAsc(ForumComment::getId));
        List<ForumComment> rootList = roots.getRecords();
        List<Long> rootIds = rootList.stream().map(ForumComment::getId).toList();
        List<ForumComment> replies = rootIds.isEmpty()
                ? List.of() : commentMapper.selectRepliesByRootIds(postId, rootIds);

        Map<Long, List<ForumComment>> repliesByRoot = new LinkedHashMap<>();
        for (ForumComment reply : replies) {
            repliesByRoot.computeIfAbsent(reply.getRootId(), k -> new ArrayList<>()).add(reply);
        }
        List<Long> userIds = new ArrayList<>();
        rootList.forEach(c -> {
            userIds.add(c.getAuthorId());
            userIds.add(c.getReplyToUserId());
        });
        replies.forEach(c -> {
            userIds.add(c.getAuthorId());
            userIds.add(c.getReplyToUserId());
        });
        Map<Long, String> names = namesOfUsers(userIds);
        Map<Long, Integer> avatars = avatarVersionsOf(userIds);

        List<ForumCommentThreadView> items = rootList.stream()
                .map(root -> new ForumCommentThreadView(
                        view(root, names, avatars),
                        repliesByRoot.getOrDefault(root.getId(), List.of()).stream()
                                .map(reply -> view(reply, names, avatars)).toList()))
                .toList();
        return new PageResult<>(items, roots.getTotal(), roots.getCurrent(), roots.getSize(), roots.getPages());
    }

    @Transactional
    public ForumCommentView create(AuthenticatedUser user, long postId, ForumCommentCreateRequest req) {
        Permissions.require(user, Permissions.FORUM_COMMENT_WRITE);
        ForumPost post = postMapper.selectByIdForUpdate(postId);
        if (post == null || post.getStatus() != ForumPostStatus.PUBLISHED) {
            throw BusinessException.notFound("帖子");
        }
        ForumCategory category = categoryService.require(post.getCategoryId());
        if (category.getStatus() != ForumCategoryStatus.ACTIVE) {
            throw BusinessException.notFound("帖子");
        }
        sensitiveWordService.check(req.content());

        ForumComment parent = null;
        if (req.parentId() != null) {
            parent = commentMapper.selectById(req.parentId());
            if (parent == null || parent.getStatus() != ForumCommentStatus.PUBLISHED
                    || !Objects.equals(parent.getPostId(), postId)) {
                throw new BusinessException(ErrorCode.FORUM_COMMENT_PARENT_INVALID);
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);

        ForumComment comment = new ForumComment();
        comment.setPostId(postId);
        comment.setAuthorId(user.userId());
        comment.setContent(req.content().trim());
        comment.setStatus(ForumCommentStatus.PUBLISHED);
        if (parent != null) {
            comment.setParentId(parent.getId());
            comment.setRootId(parent.getRootId() == null ? parent.getId() : parent.getRootId());
            comment.setReplyToUserId(parent.getAuthorId());
        }
        commentMapper.insert(comment);

        if (postMapper.incrementCommentCount(postId, now) != 1) {
            throw BusinessException.conflict("帖子状态已变化，请刷新后重试");
        }
        audit("FORUM_COMMENT_CREATE", comment.getId(), "postId", postId,
                "parentId", comment.getParentId(), "rootId", comment.getRootId());
        String replyToName = parent == null ? null : nameOf(parent.getAuthorId());
        notifyNewComment(user, post, comment, parent);
        int authorAvatar = avatarVersionOf(comment.getAuthorId());
        int replyAvatar = avatarVersionOf(comment.getReplyToUserId());
        return view(comment, user.displayName(), authorAvatar, replyToName, replyAvatar);
    }

    /**
     * 删除评论：顶级评论会级联软删除同一线程的全部回复；回复只删除自身，
     * 其下的“回复的回复”保留并在同一 root 线程中展示。评论计数按实际删除条数扣减。
     */
    @Transactional
    public void delete(AuthenticatedUser user, long commentId) {
        requireUser(user);
        ForumComment comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getStatus() != ForumCommentStatus.PUBLISHED) {
            throw BusinessException.notFound("评论");
        }
        ForumPost post = postMapper.selectByIdForUpdate(comment.getPostId());
        if (post == null || post.getStatus() == ForumPostStatus.DELETED) {
            throw BusinessException.notFound("评论");
        }
        accessService.requireCommentDeletable(user, comment);

        LocalDateTime now = LocalDateTime.now(clock);
        boolean cascade = comment.getParentId() == null;
        int removed = cascade
                ? commentMapper.markThreadDeleted(comment.getPostId(), commentId, user.userId(), now)
                : commentMapper.markDeleted(commentId, user.userId(), now);
        if (removed == 0) {
            throw BusinessException.notFound("评论");
        }
        if (postMapper.decrementCommentCount(comment.getPostId(), removed, now) != 1) {
            throw BusinessException.conflict("评论计数更新失败，请刷新后重试");
        }
        audit("FORUM_COMMENT_DELETE", commentId, "postId", comment.getPostId(),
                "removedCount", removed, "cascade", cascade);
        if (!user.userId().equals(comment.getAuthorId())
                && settingService.getBool("forum.notify.moderate", true)) {
            notificationService.notify(comment.getAuthorId(), NotificationCategory.FORUM,
                    NotificationLevel.WARNING, "你的评论被删除",
                    "你在《" + post.getTitle() + "》下的评论已被版主删除。",
                    "/forum/posts/" + post.getId(), "FORUM_COMMENT", commentId);
        }
    }

    /**
     * 论坛互动通知：帖子作者收到「新评论」，被回复人收到「有人回复你」。
     * 同一个人只收到一条：自己回复自己的评论、或作者自己回复时不会自我打扰。
     */
    private void notifyNewComment(AuthenticatedUser actor, ForumPost post, ForumComment comment, ForumComment parent) {
        String text = excerpt(comment.getContent(), 80);
        String link = "/forum/posts/" + post.getId();
        if (parent != null) {
            Long replyTo = parent.getAuthorId();
            if (replyTo != null && !replyTo.equals(actor.userId())
                    && settingService.getBool("forum.notify.reply", true)) {
                notificationService.notify(replyTo, NotificationCategory.FORUM, NotificationLevel.INFO,
                        actor.displayName() + " 回复了你的评论",
                        "在《" + post.getTitle() + "》：" + text, link, "FORUM_POST", post.getId());
            }
            if (Objects.equals(replyTo, post.getAuthorId())) {
                return;
            }
        }
        if (!Objects.equals(post.getAuthorId(), actor.userId())
                && settingService.getBool("forum.notify.comment", true)) {
            notificationService.notify(post.getAuthorId(), NotificationCategory.FORUM, NotificationLevel.INFO,
                    "你的帖子有新评论",
                    actor.displayName() + " 在《" + post.getTitle() + "》下评论：" + text,
                    link, "FORUM_POST", post.getId());
        }
    }

    private static String excerpt(String content, int max) {
        if (content == null) {
            return "";
        }
        String s = content.replaceAll("\\s+", " ").trim();
        int length = s.codePointCount(0, s.length());
        return length <= max ? s : s.substring(0, s.offsetByCodePoints(0, max)) + "…";
    }

    private ForumCommentView view(ForumComment c, Map<Long, String> names, Map<Long, Integer> avatars) {
        return new ForumCommentView(c.getId(), c.getPostId(), c.getParentId(), c.getRootId(),
                c.getAuthorId(), names.get(c.getAuthorId()),
                avatars.getOrDefault(c.getAuthorId(), 0),
                c.getReplyToUserId(), names.get(c.getReplyToUserId()),
                avatars.getOrDefault(c.getReplyToUserId(), 0),
                c.getContent(), c.getStatus(), c.getCreatedAt());
    }

    private ForumCommentView view(ForumComment c, String authorName, int authorAvatar,
                                  String replyToName, int replyAvatar) {
        return new ForumCommentView(c.getId(), c.getPostId(), c.getParentId(), c.getRootId(),
                c.getAuthorId(), authorName, authorAvatar,
                c.getReplyToUserId(), replyToName, replyAvatar,
                c.getContent(), c.getStatus(), c.getCreatedAt());
    }

    private int avatarVersionOf(Long userId) {
        return avatarVersionsOf(userId == null ? List.of() : List.of(userId)).getOrDefault(userId, 0);
    }

    private Map<Long, Integer> avatarVersionsOf(Collection<Long> userIds) {
        Set<Long> ids = new HashSet<>();
        userIds.forEach(id -> {
            if (id != null && id > 0) {
                ids.add(id);
            }
        });
        Map<Long, Integer> versions = new HashMap<>();
        if (ids.isEmpty()) {
            return versions;
        }
        userMapper.selectAvatarVersions(ids)
                .forEach(row -> versions.put(row.getId(), row.getAvatarVersion() == null ? 0 : row.getAvatarVersion()));
        return versions;
    }

    private String nameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = userMapper.selectById(userId);
        return user == null ? null : user.getDisplayName();
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

    private static AuthenticatedUser requireUser(AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("FORUM_COMMENT", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
