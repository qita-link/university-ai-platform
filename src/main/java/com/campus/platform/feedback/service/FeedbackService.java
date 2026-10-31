package com.campus.platform.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.feedback.dto.FeedbackCreateRequest;
import com.campus.platform.feedback.dto.FeedbackQuery;
import com.campus.platform.feedback.dto.FeedbackReplyRequest;
import com.campus.platform.feedback.dto.FeedbackView;
import com.campus.platform.feedback.entity.CampusFeedback;
import com.campus.platform.feedback.entity.FeedbackStatus;
import com.campus.platform.feedback.mapper.CampusFeedbackMapper;
import com.campus.platform.notification.entity.NotificationCategory;
import com.campus.platform.notification.entity.NotificationLevel;
import com.campus.platform.notification.service.NotificationService;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 校长信箱：全员提交投诉与建议并可匿名，仅系统管理员（ADMIN + feedback:handle）受理与回复。
 * <p>
 * 匿名条目对处理人也不返回提交人姓名，仅在数据库中保留 student_id 用于必要追溯。
 */
@Service
public class FeedbackService {

    static final String MODULE = "feedback";

    private final CampusFeedbackMapper feedbackMapper;
    private final SysUserMapper userMapper;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final Clock clock;

    public FeedbackService(CampusFeedbackMapper feedbackMapper, SysUserMapper userMapper,
                           NotificationService notificationService, AuditService auditService, Clock clock) {
        this.feedbackMapper = feedbackMapper;
        this.userMapper = userMapper;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public FeedbackView create(AuthenticatedUser user, FeedbackCreateRequest req) {
        Permissions.require(user, Permissions.FEEDBACK_SUBMIT);
        CampusFeedback feedback = new CampusFeedback();
        feedback.setCategory(req.category());
        feedback.setTitle(req.title().trim());
        feedback.setContent(req.content().trim());
        feedback.setAnonymous(Boolean.TRUE.equals(req.anonymous()));
        feedback.setStudentId(user.userId());
        feedback.setStatus(FeedbackStatus.OPEN);
        feedback.setVersion(0);
        feedbackMapper.insert(feedback);
        auditService.record(AuditEvent.builder(MODULE, "FEEDBACK_SUBMIT")
                .target("CAMPUS_FEEDBACK", feedback.getId())
                .detail("category", req.category())
                .detail("anonymous", feedback.getAnonymous())
                .build());
        return view(feedback, user, Map.of());
    }

    public PageResult<FeedbackView> mine(AuthenticatedUser user, FeedbackQuery q) {
        Permissions.require(user, Permissions.FEEDBACK_SUBMIT);
        LambdaQueryWrapper<CampusFeedback> w = new LambdaQueryWrapper<CampusFeedback>()
                .eq(CampusFeedback::getStudentId, user.userId());
        applyFilters(w, q);
        return page(user, q, w);
    }

    /**
     * 全部条目：仅系统管理员。权限点之外再加一层角色判断，
     * 避免将来把 feedback:handle 分配给其他角色后，非管理员也能看到全校投诉。
     */
    public PageResult<FeedbackView> list(AuthenticatedUser user, FeedbackQuery q) {
        requireHandler(user);
        LambdaQueryWrapper<CampusFeedback> w = new LambdaQueryWrapper<>();
        if (q.getStatus() == null) {
            w.ne(CampusFeedback::getStatus, FeedbackStatus.CLOSED);
        } else {
            w.eq(CampusFeedback::getStatus, q.getStatus());
        }
        applyFilters(w, q);
        return page(user, q, w);
    }

    public FeedbackView get(AuthenticatedUser user, long id) {
        CampusFeedback feedback = require(id);
        boolean owner = Objects.equals(feedback.getStudentId(), user.userId());
        if (!owner && !canHandle(user)) {
            throw BusinessException.notFound("投诉建议");
        }
        return view(feedback, user, Map.of());
    }

    /** 处理反馈是系统管理员专属：权限点与角色都要满足。 */
    private void requireHandler(AuthenticatedUser user) {
        if (!user.isAdmin()) {
            throw BusinessException.forbidden("只有系统管理员可以处理校长信箱");
        }
        Permissions.require(user, Permissions.FEEDBACK_HANDLE);
    }

    private boolean canHandle(AuthenticatedUser user) {
        return user.isAdmin() && user.hasPermission(Permissions.FEEDBACK_HANDLE);
    }

    @Transactional
    public FeedbackView reply(AuthenticatedUser user, long id, FeedbackReplyRequest req) {
        requireHandler(user);
        CampusFeedback feedback = require(id);
        if (req.status() == FeedbackStatus.OPEN) {
            throw BusinessException.rule("处理后不能回到「待受理」");
        }
        String text = req.reply() == null || req.reply().isBlank() ? null : req.reply().trim();
        if (text == null && feedback.getReply() == null) {
            throw BusinessException.badRequest("请填写回复内容");
        }
        feedback.setStatus(req.status());
        feedback.setHandlerId(user.userId());
        if (text != null) {
            feedback.setReply(text);
            feedback.setRepliedAt(LocalDateTime.now(clock));
        }
        feedbackMapper.updateById(feedback);
        auditService.record(AuditEvent.builder(MODULE, "FEEDBACK_REPLY")
                .target("CAMPUS_FEEDBACK", id)
                .detail("status", req.status())
                .build());
        notificationService.notify(feedback.getStudentId(), NotificationCategory.FEEDBACK,
                req.status() == FeedbackStatus.RESOLVED ? NotificationLevel.SUCCESS : NotificationLevel.INFO,
                "你的投诉建议有回复了",
                "《" + feedback.getTitle() + "》" + (text == null ? "状态已更新。" : "：" + text),
                "/mailbox?tab=feedback", "CAMPUS_FEEDBACK", id);
        return view(feedback, user, Map.of());
    }

    private void applyFilters(LambdaQueryWrapper<CampusFeedback> w, FeedbackQuery q) {
        if (q.getCategory() != null) {
            w.eq(CampusFeedback::getCategory, q.getCategory());
        }
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            String keyword = q.getKeyword().trim();
            w.and(x -> x.like(CampusFeedback::getTitle, keyword).or().like(CampusFeedback::getContent, keyword));
        }
    }

    private PageResult<FeedbackView> page(AuthenticatedUser user, FeedbackQuery q,
                                          LambdaQueryWrapper<CampusFeedback> w) {
        w.orderByDesc(CampusFeedback::getId);
        Page<CampusFeedback> pageRequest = q.toPage();
        IPage<CampusFeedback> page = feedbackMapper.selectPage(pageRequest, w);
        List<CampusFeedback> records = page.getRecords();
        Map<Long, SysUser> users = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        records.forEach(r -> {
            ids.add(r.getStudentId());
            if (r.getHandlerId() != null) {
                ids.add(r.getHandlerId());
            }
        });
        ids.remove(null);
        if (!ids.isEmpty()) {
            userMapper.selectBatchIds(ids).forEach(u -> users.put(u.getId(), u));
        }
        List<FeedbackView> items = new ArrayList<>();
        for (CampusFeedback feedback : records) {
            items.add(view(feedback, user, users));
        }
        return new PageResult<>(items, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    private FeedbackView view(CampusFeedback feedback, AuthenticatedUser viewer, Map<Long, SysUser> users) {
        boolean handler = canHandle(viewer);
        boolean anonymous = Boolean.TRUE.equals(feedback.getAnonymous());
        SysUser submitter = !anonymous ? users.get(feedback.getStudentId()) : null;
        if (!anonymous && submitter == null) {
            submitter = userMapper.selectById(feedback.getStudentId());
        }
        SysUser handlerUser = feedback.getHandlerId() == null ? null : users.get(feedback.getHandlerId());
        if (feedback.getHandlerId() != null && handlerUser == null) {
            handlerUser = userMapper.selectById(feedback.getHandlerId());
        }
        return new FeedbackView(feedback.getId(), feedback.getCategory(), feedback.getTitle(),
                feedback.getContent(), anonymous,
                anonymous ? null : feedback.getStudentId(),
                anonymous ? null : (submitter == null ? null : submitter.getDisplayName()),
                feedback.getStatus(),
                handlerUser == null ? null : handlerUser.getDisplayName(),
                feedback.getReply(), feedback.getRepliedAt(), handler, feedback.getCreatedAt());
    }

    private CampusFeedback require(long id) {
        CampusFeedback feedback = feedbackMapper.selectById(id);
        if (feedback == null) {
            throw BusinessException.notFound("投诉建议");
        }
        return feedback;
    }
}
