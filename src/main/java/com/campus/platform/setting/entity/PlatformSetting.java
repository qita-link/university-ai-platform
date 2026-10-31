package com.campus.platform.setting.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 平台设置（platform_setting）：键值对形式的基础开关，由管理员在「管理 → 设置」中修改。
 * <p>
 * 目前承载请假审批流程（是否需要任课教师/导员、审批顺序）等运行期可调项。
 */
@Getter
@Setter
@TableName("platform_setting")
public class PlatformSetting {

    @TableId(type = IdType.INPUT)
    private String settingKey;

    private String settingValue;

    private String description;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
