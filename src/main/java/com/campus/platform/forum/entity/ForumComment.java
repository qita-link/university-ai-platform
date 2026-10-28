package com.campus.platform.forum.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 论坛评论（forum_comment），最多两级。
 * <p>
 * 顶级评论：parentId/rootId 均为空。回复：rootId 指向顶级评论，parentId 指向被回复的评论
 * （允许回复一条回复，展示时仍归入同一 root 线程）。删除为软删除。
 */
@Getter
@Setter
@TableName("forum_comment")
public class ForumComment extends AuditableEntity {

    private Long postId;
    private Long parentId;
    private Long rootId;
    private Long authorId;
    private Long replyToUserId;
    private String content;
    private ForumCommentStatus status;
    private LocalDateTime deletedAt;
    private Long deletedBy;
}
