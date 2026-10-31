package com.campus.platform.notification.channel;

import com.campus.platform.notification.entity.DeliveryChannel;
import com.campus.platform.notification.entity.Notification;
import com.campus.platform.notification.entity.NotificationDelivery;
import org.springframework.stereotype.Component;

/**
 * 微信服务号渠道占位实现：接入时在这里调用模板消息/订阅消息接口。
 */
@Component
public class WechatChannelSender implements NotificationChannelSender {

    @Override
    public DeliveryChannel channel() {
        return DeliveryChannel.WECHAT;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void send(Notification notification, NotificationDelivery delivery) {
        throw new UnsupportedOperationException("微信服务号渠道尚未接入");
    }
}
