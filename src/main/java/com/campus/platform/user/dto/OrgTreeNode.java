package com.campus.platform.user.dto;

import com.campus.platform.user.entity.OrgStatus;
import com.campus.platform.user.entity.OrgType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "组织树节点")
public record OrgTreeNode(
        Long id,
        Long parentId,
        String code,
        String name,
        OrgType type,
        OrgStatus status,
        int sortOrder,
        List<OrgTreeNode> children) {
}
