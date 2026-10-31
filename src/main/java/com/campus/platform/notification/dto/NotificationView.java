package com.campus.platform.notification.dto;

import com.campus.platform.notification.entity.NotificationCategory;
import com.campus.platform.notification.entity.NotificationLevel;

import java.time.LocalDateTime;

public record NotificationView(
        Long id,
        NotificationCategory category,
        NotificationLevel level,
        String title,
        String body,
        String linkUrl,
        String bizType,
        Long bizId,
        boolean popupRequired,
        LocalDateTime readAt,
        LocalDateTime createdAt) {
}
