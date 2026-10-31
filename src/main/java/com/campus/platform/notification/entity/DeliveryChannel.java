package com.campus.platform.notification.entity;

/** 投递渠道。站内信不落投递表，EMAIL/WECHAT 预留给后续接入。 */
public enum DeliveryChannel {
    EMAIL,
    WECHAT;

    public String settingKey() {
        return "notify.channel." + name().toLowerCase() + ".enabled";
    }
}
