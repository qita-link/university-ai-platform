package com.campus.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 知识库分类（kb_category）。 */
@Getter
@Setter
@TableName("kb_category")
public class KbCategory extends BaseEntity {

    private Long parentId;
    private String name;
    private String description;
    private Integer sortOrder;
}
