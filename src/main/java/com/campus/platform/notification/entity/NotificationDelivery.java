package com.campus.platform.notification.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 通知投递记录：一个通知在一个渠道上一条。 */
@Getter
@Setter
@TableName("notification_delivery")
public class NotificationDelivery extends BaseEntity {

    private Long notificationId;
    private DeliveryChannel channel;
    private DeliveryStatus status;
    private Integer attempts;
    private String lastError;
    private LocalDateTime sentAt;
}
