package com.campus.platform.forum.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.forum.dto.ForumPostStatusRequest;
import com.campus.platform.forum.dto.ForumReportCreateRequest;
import com.campus.platform.forum.dto.ForumReportView;
import com.campus.platform.forum.entity.ForumComment;
import com.campus.platform.forum.entity.ForumCommentStatus;
import com.campus.platform.forum.entity.ForumPost;
import com.campus.platform.forum.entity.ForumPostStatus;
import com.campus.platform.forum.entity.ForumReport;
import com.campus.platform.forum.entity.ReportStatus;
import com.campus.platform.forum.entity.ReportTargetType;
import com.campus.platform.forum.mapper.ForumCommentMapper;
import com.campus.platform.forum.mapper.ForumPostMapper;
import com.campus.platform.forum.mapper.ForumReportMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 论坛举报与审核队列。
 * <p>
 * 处理策略：
 * <ul>
 *   <li>任何登录用户都能举报；同一目标重复举报幂等（唯一键约束，不报错）；</li>
 *   <li>只有 {@code forum:moderate} 能看队列与处理；</li>
 *   <li>举报成立：帖子转为 {@code HIDDEN}（复用版主隐藏逻辑，作者仍可见），评论走软删除；</li>
 *   <li>处理用条件更新 {@code PENDING → ACCEPTED/REJECTED}，并发处理同一举报只有一个成功。</li>
 * </ul>
 */
@Slf4j
@Service
public class ForumReportService {

    private static final String MODULE = "forum";
    private static final Set<String> REASONS =
            Set.of("SPAM", "ABUSE", "PORN", "ILLEGAL", "ACADEMIC", "OTHER");

    private final ForumReportMapper reportMapper;
    private final ForumPostMapper postMapper;
    private final ForumCommentMapper commentMapper;
    private final SysUserMapper userMapper;
    private final ForumPostService postService;
    private final ForumCommentService commentService;
    private final AuditService auditService;
    private final Clock clock;

    public ForumReportService(ForumReportMapper reportMapper, ForumPostMapper postMapper,
                              ForumCommentMapper commentMapper, SysUserMapper userMapper,
                              ForumPostService postService, ForumCommentService commentService,
                              AuditService auditService, Clock clock) {
        this.reportMapper = reportMapper;
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.userMapper = userMapper;
        this.postService = postService;
        this.commentService = commentService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** 提交举报；重复举报不报错（幂等）。 */
    @Transactional
    public Long report(AuthenticatedUser user, ForumReportCreateRequest req) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String reason = req.reason() == null ? "OTHER" : req.reason().trim().toUpperCase();
        if (!REASONS.contains(reason)) {
            throw BusinessException.badRequest("举报原因不合法");
        }
        ensureTargetExists(req.targetType(), req.targetId());

        ForumReport existing = reportMapper.selectOne(new LambdaQueryWrapper<ForumReport>()
                .eq(ForumReport::getTargetType, req.targetType())
                .eq(ForumReport::getTargetId, req.targetId())
                .eq(ForumReport::getReporterId, user.userId()));
        if (existing != null) {
            return existing.getId();
        }

        ForumReport report = new ForumReport();
        report.setTargetType(req.targetType());
        report.setTargetId(req.targetId());
        report.setReporterId(user.userId());
        report.setReason(reason);
        report.setDetail(req.detail());
        report.setStatus(ReportStatus.PENDING);
        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException ex) {
            // 并发重复提交：唯一键兜底，按已举报处理
            ForumReport again = reportMapper.selectOne(new LambdaQueryWrapper<ForumReport>()
                    .eq(ForumReport::getTargetType, req.targetType())
                    .eq(ForumReport::getTargetId, req.targetId())
                    .eq(ForumReport::getReporterId, user.userId()));
            return again == null ? null : again.getId();
        }
        auditService.record(AuditEvent.builder(MODULE, "FORUM_REPORT")
                .target("FORUM_REPORT", report.getId())
                .detail("targetType", req.targetType().name())
                .detail("targetId", req.targetId())
                .detail("reason", reason)
                .build());
        return report.getId();
    }

    /** 审核队列；status 为空表示全部。 */
    public PageResult<ForumReportView> list(AuthenticatedUser user, String status, long page, long size) {
        Permissions.require(user, Permissions.FORUM_MODERATE);
        LambdaQueryWrapper<ForumReport> q = new LambdaQueryWrapper<ForumReport>()
                .orderByAsc(ForumReport::getStatus)
                .orderByDesc(ForumReport::getCreatedAt);
        if (status != null && !status.isBlank()) {
            q.eq(ForumReport::getStatus, ReportStatus.valueOf(status.toUpperCase()));
        }
        Page<ForumReport> result = reportMapper.selectPage(new Page<>(page, size), q);
        List<ForumReport> records = result.getRecords();
        Map<Long, String> userNames = namesOf(records.stream()
                .flatMap(r -> java.util.stream.Stream.of(r.getReporterId(), r.getHandledBy()))
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, String> postTitles = titlesOf(ReportTargetType.POST,
                records.stream().filter(r -> r.getTargetType() == ReportTargetType.POST)
                        .map(ForumReport::getTargetId).collect(Collectors.toSet()));
        Map<Long, String> commentExcerpts = titlesOf(ReportTargetType.COMMENT,
                records.stream().filter(r -> r.getTargetType() == ReportTargetType.COMMENT)
                        .map(ForumReport::getTargetId).collect(Collectors.toSet()));

        List<ForumReportView> items = records.stream().map(r -> {
            boolean isPost = r.getTargetType() == ReportTargetType.POST;
            String title = isPost ? postTitles.get(r.getTargetId()) : commentExcerpts.get(r.getTargetId());
            return new ForumReportView(r.getId(), r.getTargetType(), r.getTargetId(), title,
                    isTargetVisible(r), r.getReporterId(), userNames.get(r.getReporterId()),
                    r.getReason(), r.getDetail(), r.getStatus(), r.getHandledBy(),
                    userNames.get(r.getHandledBy()), r.getHandledAt(), r.getHandleNote(), r.getCreatedAt());
        }).toList();
        return new PageResult<>(items, result.getTotal(), result.getCurrent(), result.getSize(), result.getPages());
    }

    /**
     * 处理举报：成立则隐藏帖子 / 删除评论。
     * 条件更新保证并发下只有一个处理人成功。
     */
    @Transactional
    public void handle(AuthenticatedUser user, long reportId, boolean accept, String note) {
        Permissions.require(user, Permissions.FORUM_MODERATE);
        ForumReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw BusinessException.notFound("举报记录");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = reportMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ForumReport>()
                .eq(ForumReport::getId, reportId)
                .eq(ForumReport::getStatus, ReportStatus.PENDING)
                .set(ForumReport::getStatus, accept ? ReportStatus.ACCEPTED : ReportStatus.REJECTED)
                .set(ForumReport::getHandledBy, user.userId())
                .set(ForumReport::getHandledAt, now)
                .set(ForumReport::getHandleNote, note));
        if (updated == 0) {
            throw BusinessException.conflict("该举报已被处理");
        }
        if (accept) {
            if (report.getTargetType() == ReportTargetType.POST) {
                postService.moderate(user, report.getTargetId(),
                        new ForumPostStatusRequest(ForumPostStatus.HIDDEN, null, null, "举报成立，自动隐藏"));
            } else {
                commentService.delete(user, report.getTargetId());
            }
        }
        auditService.record(AuditEvent.builder(MODULE, "FORUM_REPORT_HANDLE")
                .target("FORUM_REPORT", reportId)
                .detail("accept", accept)
                .detail("note", note)
                .build());
    }

    // ------------------------------------------------------------------ 辅助

    private void ensureTargetExists(ReportTargetType type, Long id) {
        if (id == null) {
            throw BusinessException.badRequest("举报对象不能为空");
        }
        if (type == ReportTargetType.POST) {
            ForumPost post = postMapper.selectById(id);
            if (post == null || post.getStatus() == ForumPostStatus.DELETED) {
                throw BusinessException.notFound("帖子");
            }
        } else {
            ForumComment comment = commentMapper.selectById(id);
            if (comment == null || comment.getStatus() == ForumCommentStatus.DELETED) {
                throw BusinessException.notFound("评论");
            }
        }
    }

    private boolean isTargetVisible(ForumReport report) {
        if (report.getTargetType() == ReportTargetType.POST) {
            ForumPost post = postMapper.selectById(report.getTargetId());
            return post != null && post.getStatus() == ForumPostStatus.PUBLISHED;
        }
        ForumComment comment = commentMapper.selectById(report.getTargetId());
        return comment != null && comment.getStatus() != ForumCommentStatus.DELETED;
    }

    private Map<Long, String> namesOf(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (SysUser u : userMapper.selectBatchIds(ids)) {
            names.put(u.getId(), u.getDisplayName() == null ? u.getUsername() : u.getDisplayName());
        }
        return names;
    }

    private Map<Long, String> titlesOf(ReportTargetType type, Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> titles = new HashMap<>();
        if (type == ReportTargetType.POST) {
            for (ForumPost p : postMapper.selectBatchIds(ids)) {
                titles.put(p.getId(), p.getTitle());
            }
        } else {
            for (ForumComment c : commentMapper.selectBatchIds(ids)) {
                String text = c.getContent() == null ? "" : c.getContent().replace("\n", " ");
                titles.put(c.getId(), text.length() > 60 ? text.substring(0, 60) + "…" : text);
            }
        }
        return titles;
    }
}
