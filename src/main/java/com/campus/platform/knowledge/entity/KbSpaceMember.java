package com.campus.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 知识库成员授权（kb_space_member）。 */
@Getter
@Setter
@TableName("kb_space_member")
public class KbSpaceMember extends BaseEntity {

    private Long spaceId;
    private Long userId;
    private MemberRole memberRole;
    private Long grantedBy;
}
