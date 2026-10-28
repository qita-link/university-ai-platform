package com.campus.platform.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 角色（sys_role） */
@Getter
@Setter
@TableName("sys_role")
public class SysRole extends BaseEntity {

    private String code;
    private String name;
    private String description;
    private Boolean builtIn;
}
