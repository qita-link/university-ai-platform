package com.campus.platform.forum.mapper;

import com.campus.platform.forum.entity.ForumPostStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 论坛帖子列表/检索 SQL 的投影行（带分类名与可选全文分数）。 */
@Getter
@Setter
public class ForumPostRow {

    private Long id;
    private Long categoryId;
    private String categoryName;
    private Long authorId;
    private String title;
    private String content;
    private ForumPostStatus status;
    private Boolean pinned;
    private LocalDateTime pinnedUntil;
    private Integer commentCount;
    private LocalDateTime lastActivityAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer version;
    private Double score;
}
