package com.campus.platform.forum.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

/** 论坛分类（forum_category），扁平结构，管理员维护。 */
@Getter
@Setter
@TableName("forum_category")
public class ForumCategory extends AuditableEntity {

    private String name;
    private String description;
    private Integer sortOrder;
    private ForumCategoryStatus status;
    /** 公告分类：发帖需要 forum:announce，发布后广播全员并弹窗 */
    private Boolean announcement;
}
