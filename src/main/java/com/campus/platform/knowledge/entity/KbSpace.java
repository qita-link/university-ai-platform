package com.campus.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

/** 知识库空间（kb_space）。 */
@Getter
@Setter
@TableName("kb_space")
public class KbSpace extends AuditableEntity {

    private Long categoryId;
    private String name;
    private String description;
    private SpaceVisibility visibility;
    private Long scopeOrgUnitId;
    private Long scopeCourseId;
    private Long ownerUserId;
    private SpaceStatus status;

    @Version
    private Integer version;

    public boolean isActive() {
        return status == SpaceStatus.ACTIVE;
    }
}
