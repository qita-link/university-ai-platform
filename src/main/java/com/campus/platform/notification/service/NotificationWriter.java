package com.campus.platform.notification.service;

import com.campus.platform.notification.channel.NotificationChannelSender;
import com.campus.platform.notification.entity.DeliveryChannel;
import com.campus.platform.notification.entity.DeliveryStatus;
import com.campus.platform.notification.entity.Notification;
import com.campus.platform.notification.entity.NotificationCategory;
import com.campus.platform.notification.entity.NotificationDelivery;
import com.campus.platform.notification.entity.NotificationLevel;
import com.campus.platform.notification.mapper.NotificationDeliveryMapper;
import com.campus.platform.notification.mapper.NotificationMapper;
import com.campus.platform.setting.service.SettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知写入器：独立事务（REQUIRES_NEW）。
 * <p>
 * 业务事务提交前就写入通知，可以保证「操作成功后一定能看到消息」；
 * 独立事务则保证通知写失败不会把请假审批、成绩发布等主流程标记为 rollback-only。
 * 极端情况下业务随后回滚，会留下一条对应不上的通知，这比丢通知或误回滚业务更容易接受。
 */
@Slf4j
@Service
public class NotificationWriter {

    private final NotificationMapper mapper;
    private final NotificationDeliveryMapper deliveryMapper;
    private final SettingService settingService;
    private final Map<DeliveryChannel, NotificationChannelSender> senders = new HashMap<>();
    private final Clock clock;

    public NotificationWriter(NotificationMapper mapper, NotificationDeliveryMapper deliveryMapper,
                              SettingService settingService, List<NotificationChannelSender> senders, Clock clock) {
        this.mapper = mapper;
        this.deliveryMapper = deliveryMapper;
        this.settingService = settingService;
        this.clock = clock;
        if (senders != null) {
            senders.forEach(s -> this.senders.put(s.channel(), s));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(Long userId, NotificationCategory category, NotificationLevel level,
                      String title, String body, String linkUrl, String bizType, Long bizId,
                      boolean popupRequired) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setCategory(category);
        notification.setLevel(level == null ? NotificationLevel.INFO : level);
        notification.setTitle(truncate(title, 120));
        notification.setBody(truncate(body, 1000));
        notification.setLinkUrl(truncate(linkUrl, 255));
        notification.setBizType(truncate(bizType, 32));
        notification.setBizId(bizId);
        notification.setPopupRequired(popupRequired);
        mapper.insert(notification);
        dispatch(notification);
    }

    /**
     * 全员广播：一条 INSERT ... SELECT 写完，不逐用户开事务，也不生成渠道投递记录。
     * <p>
     * 外部渠道（邮件/微信）需要逐个用户发送，放在这里会拖慢发公告的响应；
     * 后续接渠道时应改为按 notification 表异步消费，而不是在请求里循环发送。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int broadcast(NotificationCategory category, NotificationLevel level, String title, String body,
                         String linkUrl, String bizType, Long bizId, boolean popupRequired,
                         String roleCode, Long excludeUserId) {
        return mapper.broadcast(category == null ? NotificationCategory.SYSTEM.name() : category.name(),
                level == null ? NotificationLevel.INFO.name() : level.name(),
                truncate(title, 120), truncate(body, 1000), truncate(linkUrl, 255),
                truncate(bizType, 32), bizId, popupRequired, roleCode, excludeUserId,
                LocalDateTime.now(clock));
    }

    /** 按渠道开关生成投递；渠道未接入时标记 SKIPPED，站内信仍然可读。 */
    private void dispatch(Notification notification) {
        for (DeliveryChannel channel : DeliveryChannel.values()) {
            if (!settingService.getBool(channel.settingKey(), false)) {
                continue;
            }
            NotificationDelivery delivery = new NotificationDelivery();
            delivery.setNotificationId(notification.getId());
            delivery.setChannel(channel);
            delivery.setStatus(DeliveryStatus.PENDING);
            delivery.setAttempts(0);
            deliveryMapper.insert(delivery);

            NotificationChannelSender sender = senders.get(channel);
            if (sender == null || !sender.available()) {
                delivery.setStatus(DeliveryStatus.SKIPPED);
                delivery.setLastError("渠道尚未接入");
                deliveryMapper.updateById(delivery);
                continue;
            }
            try {
                sender.send(notification, delivery);
                delivery.setStatus(DeliveryStatus.SENT);
                delivery.setSentAt(LocalDateTime.now(clock));
            } catch (Exception e) {
                delivery.setStatus(DeliveryStatus.FAILED);
                delivery.setLastError(truncate(e.getMessage(), 500));
            }
            delivery.setAttempts(delivery.getAttempts() + 1);
            deliveryMapper.updateById(delivery);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.length() <= max ? value : value.substring(0, max);
    }
}
