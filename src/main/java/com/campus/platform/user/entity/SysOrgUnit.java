package com.campus.platform.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 组织单元（sys_org_unit）：学院 / 系 / 专业 / 班级。不物理删除，通过 status 停用。
 */
@Getter
@Setter
@TableName("sys_org_unit")
public class SysOrgUnit extends BaseEntity {

    private Long parentId;
    private String code;
    private String name;
    private OrgType type;
    private OrgStatus status;
    private Integer sortOrder;

    @Override
    public String toString() {
        return "SysOrgUnit[id=" + getId() + ", code=" + code + ", parentId=" + parentId + "]";
    }
}
