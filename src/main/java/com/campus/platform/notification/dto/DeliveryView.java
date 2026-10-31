package com.campus.platform.notification.dto;

import com.campus.platform.notification.entity.DeliveryChannel;
import com.campus.platform.notification.entity.DeliveryStatus;

import java.time.LocalDateTime;

public record DeliveryView(
        Long id,
        Long notificationId,
        DeliveryChannel channel,
        DeliveryStatus status,
        int attempts,
        String lastError,
        LocalDateTime sentAt,
        LocalDateTime createdAt) {
}
