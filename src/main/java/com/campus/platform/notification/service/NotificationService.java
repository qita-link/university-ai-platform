package com.campus.platform.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.common.api.PageQuery;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.notification.dto.DeliveryView;
import com.campus.platform.notification.dto.NotificationView;
import com.campus.platform.notification.entity.Notification;
import com.campus.platform.notification.entity.NotificationCategory;
import com.campus.platform.notification.entity.NotificationDelivery;
import com.campus.platform.notification.entity.NotificationLevel;
import com.campus.platform.notification.mapper.NotificationDeliveryMapper;
import com.campus.platform.notification.mapper.NotificationMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 站内通知：业务状态变更后调用 {@link #notify}，写入站内信并按已开启的渠道生成投递记录。
 * <p>
 * 写入在 {@link NotificationWriter} 的独立事务里完成：通知失败只记日志，不影响主流程。
 * 外部渠道（邮件/微信）通过 {@code NotificationChannelSender} 扩展，当前占位实现会把投递标记为 SKIPPED。
 * <p>
 * 官方公告用 {@link #broadcast} 一条 SQL 广播全员，并带 {@code popupRequired} 让前端弹窗；
 * 弹窗只展示「未读且需要弹窗」的通知，关闭弹窗即标为已读，不会反复打扰。
 */
@Slf4j
@Service
public class NotificationService {

    private final NotificationMapper mapper;
    private final NotificationDeliveryMapper deliveryMapper;
    private final NotificationWriter writer;
    private final Clock clock;

    public NotificationService(NotificationMapper mapper, NotificationDeliveryMapper deliveryMapper,
                               NotificationWriter writer, Clock clock) {
        this.mapper = mapper;
        this.deliveryMapper = deliveryMapper;
        this.writer = writer;
        this.clock = clock;
    }

    /** 发给一个用户；失败只记日志。 */
    public void notify(Long userId, NotificationCategory category, NotificationLevel level,
                       String title, String body, String linkUrl, String bizType, Long bizId) {
        notify(userId, category, level, title, body, linkUrl, bizType, bizId, false);
    }

    /** 发给一个用户，可要求登录后弹窗（官方公告）。 */
    public void notify(Long userId, NotificationCategory category, NotificationLevel level,
                       String title, String body, String linkUrl, String bizType, Long bizId,
                       boolean popupRequired) {
        if (userId == null) {
            return;
        }
        try {
            writer.write(userId, category, level, title, body, linkUrl, bizType, bizId, popupRequired);
        } catch (Exception e) {
            log.warn("通知写入失败 user={} title={} err={}", userId, title, e.getMessage());
        }
    }

    /** 批量发给多个用户（去重、忽略空值）。 */
    public void notifyAll(Collection<Long> userIds, NotificationCategory category, NotificationLevel level,
                          String title, String body, String linkUrl, String bizType, Long bizId) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        userIds.stream().filter(java.util.Objects::nonNull).distinct()
                .forEach(id -> notify(id, category, level, title, body, linkUrl, bizType, bizId, false));
    }

    /**
     * 广播给全校（或某个角色）的启用用户，返回写入条数。
     * 业务事务内调用，广播失败只记日志，不影响公告本身发布成功。
     */
    public int broadcast(NotificationCategory category, NotificationLevel level, String title, String body,
                         String linkUrl, String bizType, Long bizId, boolean popupRequired,
                         String roleCode, Long excludeUserId) {
        try {
            return writer.broadcast(category, level, title, body, linkUrl, bizType, bizId,
                    popupRequired, roleCode, excludeUserId);
        } catch (Exception e) {
            log.warn("公告广播失败 bizType={} bizId={} err={}", bizType, bizId, e.getMessage());
            return 0;
        }
    }

    public PageResult<NotificationView> list(AuthenticatedUser user, boolean unreadOnly,
                                             NotificationCategory category, PageQuery query) {
        LambdaQueryWrapper<Notification> w = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, user.userId());
        if (unreadOnly) {
            w.isNull(Notification::getReadAt);
        }
        if (category != null) {
            w.eq(Notification::getCategory, category);
        }
        w.orderByDesc(Notification::getId);
        Page<Notification> pageRequest = query.toPage();
        IPage<Notification> page = mapper.selectPage(pageRequest, w);
        List<NotificationView> items = page.getRecords().stream().map(NotificationService::view).toList();
        return new PageResult<>(items, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /** 待弹窗的官方公告：未读 + 需要弹窗，按时间正序，最多 5 条。 */
    public List<NotificationView> popups(AuthenticatedUser user, int limit) {
        int size = Math.max(1, Math.min(limit, 10));
        List<Notification> rows = mapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, user.userId())
                .eq(Notification::getPopupRequired, true)
                .isNull(Notification::getReadAt)
                .orderByAsc(Notification::getId)
                .last("LIMIT " + size));
        return rows.stream().map(NotificationService::view).toList();
    }

    public long unreadCount(AuthenticatedUser user) {
        return mapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, user.userId())
                .isNull(Notification::getReadAt));
    }

    public void markRead(AuthenticatedUser user, long id) {
        Notification notification = mapper.selectById(id);
        if (notification == null || !notification.getUserId().equals(user.userId())) {
            throw BusinessException.notFound("通知");
        }
        mapper.markRead(id, user.userId(), LocalDateTime.now(clock));
    }

    public int markAllRead(AuthenticatedUser user) {
        return mapper.markAllRead(user.userId(), LocalDateTime.now(clock));
    }

    /** 投递记录：运维排查用，需 system:config。 */
    public PageResult<DeliveryView> deliveries(AuthenticatedUser user, PageQuery query) {
        Permissions.require(user, "system:config");
        Page<NotificationDelivery> pageRequest = query.toPage();
        IPage<NotificationDelivery> page = deliveryMapper.selectPage(pageRequest,
                new LambdaQueryWrapper<NotificationDelivery>().orderByDesc(NotificationDelivery::getId));
        List<DeliveryView> items = page.getRecords().stream()
                .map(d -> new DeliveryView(d.getId(), d.getNotificationId(), d.getChannel(), d.getStatus(),
                        d.getAttempts() == null ? 0 : d.getAttempts(), d.getLastError(), d.getSentAt(), d.getCreatedAt()))
                .toList();
        return new PageResult<>(items, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    private static NotificationView view(Notification n) {
        return new NotificationView(n.getId(), n.getCategory(), n.getLevel(), n.getTitle(), n.getBody(),
                n.getLinkUrl(), n.getBizType(), n.getBizId(), Boolean.TRUE.equals(n.getPopupRequired()),
                n.getReadAt(), n.getCreatedAt());
    }
}
