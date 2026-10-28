package com.campus.platform.user.dto;

import com.campus.platform.user.entity.OrgType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 新建或修改组织单元（PUT 为整体更新，所有字段都需要提供）。
 */
@Schema(description = "组织单元")
public record OrgUnitRequest(
        @Schema(description = "上级组织 ID，顶级为空") @Positive Long parentId,
        @Schema(description = "组织编码，全局唯一", example = "CS")
        @NotBlank(message = "编码不能为空")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$", message = "编码只能包含字母、数字、下划线和短横线，最长 64 位")
        String code,
        @Schema(description = "名称", example = "计算机学院")
        @NotBlank(message = "名称不能为空") @Size(max = 128, message = "名称最长 128 字") String name,
        @Schema(description = "类型：COLLEGE/DEPARTMENT/MAJOR/CLASS，下级类型必须低于上级")
        @NotNull(message = "类型不能为空") OrgType type,
        @Schema(description = "同级排序，越小越靠前", defaultValue = "0")
        @Min(value = 0, message = "排序最小为 0") @Max(value = 100000, message = "排序值过大") Integer sortOrder) {
}
