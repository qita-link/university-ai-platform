package com.campus.platform.forum.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 论坛敏感词（forum_sensitive_word）。
 * <p>
 * 发帖、改帖、评论时做子串匹配，命中即拒绝落库。词库由管理端维护，服务端按 TTL 缓存。
 */
@Getter
@Setter
@TableName("forum_sensitive_word")
public class ForumSensitiveWord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String word;
    /** ACADEMIC/ILLEGAL/SPAM/ABUSE */
    private String category;
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
