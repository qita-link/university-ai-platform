package com.campus.platform.user.dto;

import com.campus.platform.user.entity.SysPermission;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "权限点")
public record PermissionView(Long id, String code, String name, String module, String description) {

    public static PermissionView from(SysPermission p) {
        return new PermissionView(p.getId(), p.getCode(), p.getName(), p.getModule(), p.getDescription());
    }
}
