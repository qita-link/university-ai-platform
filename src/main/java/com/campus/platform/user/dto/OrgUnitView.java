package com.campus.platform.user.dto;

import com.campus.platform.user.entity.OrgStatus;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "组织单元")
public record OrgUnitView(
        Long id,
        Long parentId,
        String code,
        String name,
        OrgType type,
        OrgStatus status,
        int sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static OrgUnitView from(SysOrgUnit o) {
        return new OrgUnitView(o.getId(), o.getParentId(), o.getCode(), o.getName(), o.getType(), o.getStatus(),
                o.getSortOrder() == null ? 0 : o.getSortOrder(), o.getCreatedAt(), o.getUpdatedAt());
    }
}
