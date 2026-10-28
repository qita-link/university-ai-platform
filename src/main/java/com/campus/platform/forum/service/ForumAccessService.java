package com.campus.platform.forum.service;

import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.forum.entity.ForumCategory;
import com.campus.platform.forum.entity.ForumCategoryStatus;
import com.campus.platform.forum.entity.ForumComment;
import com.campus.platform.forum.entity.ForumPost;
import com.campus.platform.forum.entity.ForumPostStatus;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 论坛访问控制。
 * <p>
 * 规则集中在这里，REST Controller 与阶段七 AI 工具共用：
 * <ul>
 *   <li>读：登录即可；帖子已删除、分类已停用 → 404；隐藏帖子仅作者本人和版主可见；</li>
 *   <li>写：发帖/评论需要对应功能权限；修改/删除限作者本人，版主/管理员可管理全部；</li>
 *   <li>版主权限点 {@code forum:moderate} 可隐藏/恢复/置顶以及编辑/删除任意帖子、删除任意评论。</li>
 * </ul>
 * 无权读取统一返回 404，避免通过 ID 探测隐藏/已删除内容。
 */
@Service
public class ForumAccessService {

    public boolean isModerator(AuthenticatedUser user) {
        return user != null && user.hasPermission(Permissions.FORUM_MODERATE);
    }

    public void requireModerator(AuthenticatedUser user) {
        Permissions.require(user, Permissions.FORUM_MODERATE);
    }

    /** 帖子是否可读：分类必须启用；隐藏帖子仅作者/版主可读；已删除不可读。 */
    public boolean canRead(AuthenticatedUser user, ForumPost post, ForumCategory category) {
        if (user == null || post == null || category == null) {
            return false;
        }
        if (post.getStatus() == null || post.getStatus() == ForumPostStatus.DELETED) {
            return false;
        }
        if (category.getStatus() != ForumCategoryStatus.ACTIVE) {
            return false;
        }
        if (post.getStatus() == ForumPostStatus.HIDDEN) {
            return isModerator(user) || Objects.equals(post.getAuthorId(), user.userId());
        }
        return true;
    }

    public void requireReadable(AuthenticatedUser user, ForumPost post, ForumCategory category) {
        if (!canRead(user, post, category)) {
            throw BusinessException.notFound("帖子");
        }
    }

    /** 作者本人且仍有发帖权限，或版主。 */
    public boolean canEdit(AuthenticatedUser user, ForumPost post) {
        if (user == null || post == null || post.getStatus() == ForumPostStatus.DELETED) {
            return false;
        }
        if (isModerator(user)) {
            return true;
        }
        return Objects.equals(post.getAuthorId(), user.userId())
                && user.hasPermission(Permissions.FORUM_POST_WRITE);
    }

    public void requireEditable(AuthenticatedUser user, ForumPost post) {
        if (!canEdit(user, post)) {
            throw BusinessException.forbidden("只能修改自己的帖子");
        }
    }

    public boolean canDelete(AuthenticatedUser user, ForumPost post) {
        if (user == null || post == null || post.getStatus() == ForumPostStatus.DELETED) {
            return false;
        }
        return isModerator(user)
                || (Objects.equals(post.getAuthorId(), user.userId())
                && user.hasPermission(Permissions.FORUM_POST_WRITE));
    }

    public void requireDeletable(AuthenticatedUser user, ForumPost post) {
        if (!canDelete(user, post)) {
            throw BusinessException.forbidden("只能删除自己的帖子");
        }
    }

    /** 评论作者本人（仍有评论权限）或版主。 */
    public boolean canDeleteComment(AuthenticatedUser user, ForumComment comment) {
        if (user == null || comment == null) {
            return false;
        }
        if (isModerator(user)) {
            return true;
        }
        return Objects.equals(comment.getAuthorId(), user.userId())
                && user.hasPermission(Permissions.FORUM_COMMENT_WRITE);
    }

    public void requireCommentDeletable(AuthenticatedUser user, ForumComment comment) {
        if (!canDeleteComment(user, comment)) {
            throw BusinessException.forbidden("只能删除自己的评论");
        }
    }
}
