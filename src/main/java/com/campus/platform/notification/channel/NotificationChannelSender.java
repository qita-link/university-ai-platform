package com.campus.platform.notification.channel;

import com.campus.platform.notification.entity.DeliveryChannel;
import com.campus.platform.notification.entity.Notification;
import com.campus.platform.notification.entity.NotificationDelivery;

/**
 * 外部渠道发送器。后续接入邮件或微信服务号时，实现本接口并注册为 Spring Bean 即可，
 * 通知服务会自动发现；渠道开关仍由 platform_setting 控制。
 */
public interface NotificationChannelSender {

    DeliveryChannel channel();

    /** 发送器是否已具备发送条件（SDK/密钥/模板均已配置）。 */
    boolean available();

    void send(Notification notification, NotificationDelivery delivery) throws Exception;
}
