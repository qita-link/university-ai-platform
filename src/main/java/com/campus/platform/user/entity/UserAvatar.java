package com.campus.platform.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户头像（user_avatar）：二进制单独存放，避免每次读用户都带上图片。
 * <p>
 * 以 user_id 为主键（一个用户一张）；删除用户时级联清理。
 */
@Getter
@Setter
@TableName("user_avatar")
public class UserAvatar {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private String contentType;
    private Integer sizeBytes;
    private byte[] bytes;
    private LocalDateTime updatedAt;
}
