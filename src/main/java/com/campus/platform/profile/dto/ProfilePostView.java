package com.campus.platform.profile.dto;

import com.campus.platform.forum.entity.ForumPostStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "我发过的帖子（个人资料页）")
public record ProfilePostView(
        Long id,
        String title,
        String categoryName,
        ForumPostStatus status,
        boolean pinned,
        int commentCount,
        LocalDateTime lastActivityAt,
        LocalDateTime createdAt,
        /** 内容被修改过才有值：最近一次编辑时间 */
        LocalDateTime editedAt,
        /** 最近一次编辑者姓名；自己编辑就是作者本人 */
        String editorName,
        /** 编辑者角色编码，前端转成「管理员/教师」等字样 */
        String editorRole) {
}
