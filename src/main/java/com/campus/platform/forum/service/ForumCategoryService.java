package com.campus.platform.forum.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.forum.dto.ForumCategoryRequest;
import com.campus.platform.forum.dto.ForumCategoryView;
import com.campus.platform.forum.entity.ForumCategory;
import com.campus.platform.forum.entity.ForumCategoryStatus;
import com.campus.platform.forum.entity.ForumPost;
import com.campus.platform.forum.entity.ForumPostStatus;
import com.campus.platform.forum.mapper.CategoryPostCount;
import com.campus.platform.forum.mapper.ForumCategoryMapper;
import com.campus.platform.forum.mapper.ForumPostMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 论坛分类管理：扁平结构，管理员维护；停用分类会隐藏其下帖子。 */
@Service
public class ForumCategoryService {

    static final String MODULE = "forum";

    private final ForumCategoryMapper categoryMapper;
    private final ForumPostMapper postMapper;
    private final AuditService auditService;
    private final Clock clock;

    public ForumCategoryService(ForumCategoryMapper categoryMapper, ForumPostMapper postMapper,
                                AuditService auditService, Clock clock) {
        this.categoryMapper = categoryMapper;
        this.postMapper = postMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * 分类列表。默认只返回 ACTIVE；{@code includeDisabled=true} 需要 forum:category:manage。
     * postCount 只统计 PUBLISHED 帖子，用于前端展示板块活跃度。
     */
    public List<ForumCategoryView> list(AuthenticatedUser user, boolean includeDisabled) {
        requireUser(user);
        if (includeDisabled) {
            Permissions.require(user, Permissions.FORUM_CATEGORY_MANAGE);
        }
        LambdaQueryWrapper<ForumCategory> w = new LambdaQueryWrapper<ForumCategory>()
                .eq(!includeDisabled, ForumCategory::getStatus, ForumCategoryStatus.ACTIVE)
                .orderByAsc(ForumCategory::getSortOrder)
                .orderByAsc(ForumCategory::getId);
        List<ForumCategory> categories = categoryMapper.selectList(w);
        Map<Long, Long> counts = postCounts(categories.stream().map(ForumCategory::getId).toList());
        return categories.stream().map(c -> view(c, counts.getOrDefault(c.getId(), 0L))).toList();
    }

    public ForumCategoryView get(AuthenticatedUser user, long id) {
        requireUser(user);
        ForumCategory category = require(id);
        if (category.getStatus() != ForumCategoryStatus.ACTIVE
                && (user == null || !user.hasPermission(Permissions.FORUM_CATEGORY_MANAGE))) {
            throw BusinessException.notFound("分类");
        }
        return view(category, countPublished(id));
    }

    @Transactional
    public ForumCategoryView create(AuthenticatedUser user, ForumCategoryRequest req) {
        Permissions.require(user, Permissions.FORUM_CATEGORY_MANAGE);
        String name = req.name().trim();
        requireNameUnique(name, null);
        ForumCategory category = new ForumCategory();
        category.setName(name);
        category.setDescription(blankToNull(req.description()));
        category.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        category.setStatus(req.status() == null ? ForumCategoryStatus.ACTIVE : req.status());
        category.setAnnouncement(Boolean.TRUE.equals(req.announcement()));
        categoryMapper.insert(category);
        audit("FORUM_CATEGORY_CREATE", category.getId(), "name", name, "status", category.getStatus(),
                "announcement", category.getAnnouncement());
        return view(category, 0L);
    }

    @Transactional
    public ForumCategoryView update(AuthenticatedUser user, long id, ForumCategoryRequest req) {
        Permissions.require(user, Permissions.FORUM_CATEGORY_MANAGE);
        ForumCategory category = require(id);
        String name = req.name().trim();
        requireNameUnique(name, id);
        ForumCategoryStatus status = req.status() == null ? category.getStatus() : req.status();
        boolean announcement = req.announcement() == null
                ? Boolean.TRUE.equals(category.getAnnouncement()) : req.announcement();
        int updated = categoryMapper.update(null, new LambdaUpdateWrapper<ForumCategory>()
                .eq(ForumCategory::getId, id)
                .set(ForumCategory::getName, name)
                .set(ForumCategory::getDescription, blankToNull(req.description()))
                .set(ForumCategory::getSortOrder, req.sortOrder() == null ? 0 : req.sortOrder())
                .set(ForumCategory::getStatus, status)
                .set(ForumCategory::getAnnouncement, announcement)
                .set(ForumCategory::getUpdatedBy, user.userId())
                .set(ForumCategory::getUpdatedAt, LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.conflict("分类已被其他人修改，请刷新后重试");
        }
        audit("FORUM_CATEGORY_UPDATE", id, "name", name,
                "statusFrom", category.getStatus(), "statusTo", status,
                "announcement", announcement);
        ForumCategory updatedCategory = categoryMapper.selectById(id);
        if (updatedCategory == null) {
            throw BusinessException.notFound("分类");
        }
        return view(updatedCategory, countPublished(id));
    }

    @Transactional
    public void delete(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.FORUM_CATEGORY_MANAGE);
        ForumCategory category = require(id);
        long posts = postMapper.selectCount(new LambdaQueryWrapper<ForumPost>()
                .eq(ForumPost::getCategoryId, id));
        if (posts > 0) {
            throw new BusinessException(ErrorCode.FORUM_CATEGORY_IN_USE,
                    "分类下仍有 " + posts + " 个帖子（含已删除），不能删除");
        }
        categoryMapper.deleteById(id);
        audit("FORUM_CATEGORY_DELETE", id, "name", category.getName());
    }

    /** 发帖/改帖时要求分类存在且启用。 */
    public ForumCategory requireActive(long id) {
        ForumCategory category = categoryMapper.selectById(id);
        if (category == null || category.getStatus() != ForumCategoryStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.FORUM_CATEGORY_DISABLED);
        }
        return category;
    }

    public ForumCategory require(long id) {
        ForumCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw BusinessException.notFound("分类");
        }
        return category;
    }

    private long countPublished(long categoryId) {
        return postMapper.selectCount(new LambdaQueryWrapper<ForumPost>()
                .eq(ForumPost::getCategoryId, categoryId)
                .eq(ForumPost::getStatus, ForumPostStatus.PUBLISHED));
    }

    private Map<Long, Long> postCounts(List<Long> categoryIds) {
        Map<Long, Long> result = new HashMap<>();
        if (categoryIds.isEmpty()) {
            return result;
        }
        Set<Long> ids = categoryIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return result;
        }
        for (CategoryPostCount row : postMapper.countPublishedByCategories(ids)) {
            if (row.getCategoryId() != null) {
                result.put(row.getCategoryId(), row.getTotal() == null ? 0L : row.getTotal());
            }
        }
        return result;
    }

    private void requireNameUnique(String name, Long selfId) {
        long count = categoryMapper.selectCount(new LambdaQueryWrapper<ForumCategory>()
                .eq(ForumCategory::getName, name)
                .ne(selfId != null, ForumCategory::getId, selfId));
        if (count > 0) {
            throw BusinessException.conflict("分类名称已存在");
        }
    }

    private static ForumCategoryView view(ForumCategory c, long postCount) {
        return new ForumCategoryView(c.getId(), c.getName(), c.getDescription(),
                c.getSortOrder() == null ? 0 : c.getSortOrder(), c.getStatus(),
                Boolean.TRUE.equals(c.getAnnouncement()), postCount,
                c.getCreatedAt(), c.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static AuthenticatedUser requireUser(AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("FORUM_CATEGORY", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
