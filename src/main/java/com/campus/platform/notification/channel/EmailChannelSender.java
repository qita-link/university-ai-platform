package com.campus.platform.notification.channel;

import com.campus.platform.notification.entity.DeliveryChannel;
import com.campus.platform.notification.entity.Notification;
import com.campus.platform.notification.entity.NotificationDelivery;
import org.springframework.stereotype.Component;

/**
 * 邮件渠道占位实现：接口与投递表已就绪，接入时替换 send 的实现（SMTP 或邮件服务 API）。
 * 当前 available() 为 false，开启开关后投递记录会标记 SKIPPED，站内信不受影响。
 */
@Component
public class EmailChannelSender implements NotificationChannelSender {

    @Override
    public DeliveryChannel channel() {
        return DeliveryChannel.EMAIL;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void send(Notification notification, NotificationDelivery delivery) {
        throw new UnsupportedOperationException("邮件渠道尚未接入");
    }
}
