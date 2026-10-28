package com.campus.platform.forum.mapper;

import lombok.Getter;
import lombok.Setter;

/** 分类下已发布帖子数量。 */
@Getter
@Setter
public class CategoryPostCount {

    private Long categoryId;
    private Long total;
}
