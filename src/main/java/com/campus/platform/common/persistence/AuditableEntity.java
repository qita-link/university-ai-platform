package com.campus.platform.common.persistence;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;

/**
 * 带创建人/修改人字段的实体基类，取值为当前登录用户 ID（系统任务为空）。
 */
@Getter
@Setter
public abstract class AuditableEntity extends BaseEntity {

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;
}
