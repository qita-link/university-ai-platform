package com.campus.platform.forum.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 论坛帖子（forum_post）。
 * <p>
 * 软删除：status=DELETED 并记录 deleted_at/deleted_by；评论保留用于追溯。
 * version 只跟踪帖子内容修改，评论计数/最后活跃时间的更新不递增它。
 */
@Getter
@Setter
@TableName("forum_post")
public class ForumPost extends AuditableEntity {

    private Long categoryId;
    private Long authorId;
    private String title;
    private String content;
    private ForumPostStatus status;
    private Boolean pinned;
    private Integer commentCount;
    private LocalDateTime lastActivityAt;
    private LocalDateTime deletedAt;
    private Long deletedBy;

    @Version
    private Integer version;
}
