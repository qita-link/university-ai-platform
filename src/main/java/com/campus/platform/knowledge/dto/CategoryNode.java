package com.campus.platform.knowledge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "知识库分类树节点")
public record CategoryNode(Long id, Long parentId, String name, String description, int sortOrder,
                           List<CategoryNode> children) {
}
