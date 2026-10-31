package com.campus.platform.notification.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 站内通知（notification）。
 * <p>
 * 只追加、不修改内容；已读通过 {@code read_at} 表达。业务侧只在成功的状态变更后写入，
 * 通知失败不应影响主流程（调用方负责 try/catch）。
 */
@Getter
@Setter
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private NotificationCategory category;
    private NotificationLevel level;
    private String title;
    private String body;
    private String linkUrl;
    private String bizType;
    private Long bizId;
    /** 官方公告：登录后弹窗提示；关闭弹窗即视为已读 */
    private Boolean popupRequired;
    private LocalDateTime readAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
