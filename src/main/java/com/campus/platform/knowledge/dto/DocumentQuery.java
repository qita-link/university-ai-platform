package com.campus.platform.knowledge.dto;

import com.campus.platform.common.api.PageQuery;
import com.campus.platform.knowledge.entity.DocumentStatus;
import com.campus.platform.knowledge.entity.ProcessStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "知识库文档查询")
public class DocumentQuery extends PageQuery {

    @Schema(description = "按标题/文件名模糊匹配")
    @Size(max = 255)
    private String keyword;

    @Schema(description = "处理状态")
    private ProcessStatus processStatus;

    @Schema(description = "文档状态，默认 ACTIVE")
    private DocumentStatus status;
}
