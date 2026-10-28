package com.campus.platform.ai.dto;

import com.campus.platform.ai.entity.AiToolCallStatus;
import com.campus.platform.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "AI 工具调用记录查询")
public class AiToolCallQuery extends PageQuery {

    @Schema(description = "状态筛选")
    private AiToolCallStatus status;

    @Schema(description = "工具名筛选")
    @Size(max = 64)
    private String toolName;

    @Schema(description = "按用户筛选；仅管理员可用")
    @Positive
    private Long userId;
}
