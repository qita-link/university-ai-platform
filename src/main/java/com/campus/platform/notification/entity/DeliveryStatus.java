package com.campus.platform.notification.entity;

/** 投递状态。SKIPPED 表示渠道已开启但发送器尚未接入，消息仍保存在站内信。 */
public enum DeliveryStatus {
    PENDING,
    SENT,
    FAILED,
    SKIPPED
}
