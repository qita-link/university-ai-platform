package com.campus.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.knowledge.dto.CategoryNode;
import com.campus.platform.knowledge.dto.CategoryRequest;
import com.campus.platform.knowledge.entity.KbCategory;
import com.campus.platform.knowledge.entity.KbSpace;
import com.campus.platform.knowledge.mapper.KbCategoryMapper;
import com.campus.platform.knowledge.mapper.KbSpaceMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 知识库分类管理。分类最多两级，删除前必须没有子分类和空间。 */
@Service
public class KnowledgeCategoryService {

    static final String MODULE = "knowledge";
    private static final int MAX_DEPTH = 2;

    private final KbCategoryMapper categoryMapper;
    private final KbSpaceMapper spaceMapper;
    private final AuditService auditService;

    public KnowledgeCategoryService(KbCategoryMapper categoryMapper, KbSpaceMapper spaceMapper,
                                    AuditService auditService) {
        this.categoryMapper = categoryMapper;
        this.spaceMapper = spaceMapper;
        this.auditService = auditService;
    }

    public List<CategoryNode> tree(AuthenticatedUser user) {
        Permissions.require(user, Permissions.KB_SPACE_READ);
        List<KbCategory> all = categoryMapper.selectList(new LambdaQueryWrapper<KbCategory>()
                .orderByAsc(KbCategory::getSortOrder).orderByAsc(KbCategory::getId));
        Map<Long, List<KbCategory>> children = new HashMap<>();
        List<KbCategory> roots = new ArrayList<>();
        for (KbCategory c : all) {
            if (c.getParentId() == null) {
                roots.add(c);
            } else {
                children.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
            }
        }
        return roots.stream().sorted(ORDER).map(c -> toNode(c, children, 0)).toList();
    }

    @Transactional
    public CategoryNode create(AuthenticatedUser user, CategoryRequest req) {
        Permissions.require(user, Permissions.KB_CATEGORY_MANAGE);
        Long parentId = req.parentId();
        if (parentId != null) {
            KbCategory parent = categoryMapper.selectById(parentId);
            if (parent == null) {
                throw new BusinessException(ErrorCode.ORG_INVALID_PARENT, "上级分类不存在");
            }
            if (parent.getParentId() != null) {
                throw new BusinessException(ErrorCode.KB_CATEGORY_DEPTH_EXCEEDED);
            }
        }
        requireNameUnique(parentId, req.name().trim(), null);
        KbCategory c = new KbCategory();
        c.setParentId(parentId);
        c.setName(req.name().trim());
        c.setDescription(blankToNull(req.description()));
        c.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        categoryMapper.insert(c);
        audit("KB_CATEGORY_CREATE", c.getId(), "name", c.getName(), "parentId", parentId);
        return new CategoryNode(c.getId(), c.getParentId(), c.getName(), c.getDescription(), c.getSortOrder(),
                List.of());
    }

    @Transactional
    public CategoryNode update(AuthenticatedUser user, long id, CategoryRequest req) {
        Permissions.require(user, Permissions.KB_CATEGORY_MANAGE);
        KbCategory category = require(id);
        Long newParentId = req.parentId();
        if (newParentId != null) {
            if (newParentId == id) {
                throw new BusinessException(ErrorCode.ORG_INVALID_PARENT, "分类不能作为自己的上级");
            }
            KbCategory parent = categoryMapper.selectById(newParentId);
            if (parent == null) {
                throw new BusinessException(ErrorCode.ORG_INVALID_PARENT, "上级分类不存在");
            }
            if (parent.getParentId() != null) {
                throw new BusinessException(ErrorCode.KB_CATEGORY_DEPTH_EXCEEDED);
            }
            // 有子分类时不能再挂到别人下面，否则会超过两级
            long children = categoryMapper.selectCount(new LambdaQueryWrapper<KbCategory>()
                    .eq(KbCategory::getParentId, id));
            if (children > 0) {
                throw new BusinessException(ErrorCode.KB_CATEGORY_DEPTH_EXCEEDED, "有子分类的分类不能移动");
            }
        }
        requireNameUnique(newParentId, req.name().trim(), id);
        categoryMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KbCategory>()
                .eq(KbCategory::getId, id)
                .set(KbCategory::getParentId, newParentId)
                .set(KbCategory::getName, req.name().trim())
                .set(KbCategory::getDescription, blankToNull(req.description()))
                .set(KbCategory::getSortOrder, req.sortOrder() == null ? 0 : req.sortOrder()));
        audit("KB_CATEGORY_UPDATE", id, "name", req.name(), "parentId", newParentId);
        return new CategoryNode(id, newParentId, req.name().trim(), blankToNull(req.description()),
                req.sortOrder() == null ? 0 : req.sortOrder(), List.of());
    }

    @Transactional
    public void delete(AuthenticatedUser user, long id) {
        Permissions.require(user, Permissions.KB_CATEGORY_MANAGE);
        require(id);
        long children = categoryMapper.selectCount(new LambdaQueryWrapper<KbCategory>()
                .eq(KbCategory::getParentId, id));
        if (children > 0) {
            throw new BusinessException(ErrorCode.KB_CATEGORY_IN_USE, "分类下仍有子分类");
        }
        long spaces = spaceMapper.selectCount(new LambdaQueryWrapper<KbSpace>()
                .eq(KbSpace::getCategoryId, id));
        if (spaces > 0) {
            throw new BusinessException(ErrorCode.KB_CATEGORY_IN_USE);
        }
        categoryMapper.deleteById(id);
        audit("KB_CATEGORY_DELETE", id);
    }

    public KbCategory require(Long id) {
        KbCategory category = id == null ? null : categoryMapper.selectById(id);
        if (category == null) {
            throw BusinessException.notFound("分类");
        }
        return category;
    }

    private void requireNameUnique(Long parentId, String name, Long selfId) {
        LambdaQueryWrapper<KbCategory> w = new LambdaQueryWrapper<KbCategory>()
                .eq(KbCategory::getName, name)
                .ne(selfId != null, KbCategory::getId, selfId);
        if (parentId == null) {
            w.isNull(KbCategory::getParentId);
        } else {
            w.eq(KbCategory::getParentId, parentId);
        }
        if (categoryMapper.selectCount(w) > 0) {
            throw BusinessException.conflict("同级分类名称已存在");
        }
    }

    private static final Comparator<KbCategory> ORDER = Comparator
            .comparing((KbCategory c) -> c.getSortOrder() == null ? 0 : c.getSortOrder())
            .thenComparing(KbCategory::getId);

    private static CategoryNode toNode(KbCategory c, Map<Long, List<KbCategory>> children, int depth) {
        if (depth >= MAX_DEPTH) {
            return new CategoryNode(c.getId(), c.getParentId(), c.getName(), c.getDescription(),
                    c.getSortOrder() == null ? 0 : c.getSortOrder(), List.of());
        }
        List<CategoryNode> childNodes = children.getOrDefault(c.getId(), List.of()).stream()
                .sorted(ORDER)
                .map(child -> toNode(child, children, depth + 1))
                .toList();
        return new CategoryNode(c.getId(), c.getParentId(), c.getName(), c.getDescription(),
                c.getSortOrder() == null ? 0 : c.getSortOrder(), childNodes);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("KB_CATEGORY", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
