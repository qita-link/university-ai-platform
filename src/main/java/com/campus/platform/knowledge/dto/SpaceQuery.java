package com.campus.platform.knowledge.dto;

import com.campus.platform.common.api.PageQuery;
import com.campus.platform.knowledge.entity.SpaceVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "知识库空间查询")
public class SpaceQuery extends PageQuery {

    @Schema(description = "按名称模糊匹配")
    @Size(max = 128)
    private String keyword;

    @Schema(description = "分类 ID")
    @Positive
    private Long categoryId;

    @Schema(description = "可见范围")
    private SpaceVisibility visibility;

    @Schema(description = "是否包含自己可管理的归档空间", defaultValue = "false")
    private boolean includeArchived;
}
