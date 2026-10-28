package com.campus.platform.course.dto;

import com.campus.platform.common.api.PageQuery;
import com.campus.platform.course.entity.LeaveStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "请假查询")
public class LeaveQuery extends PageQuery {

    @Schema(description = "状态筛选；为空时返回全部")
    private LeaveStatus status;

    @Schema(description = "学期 ID")
    @Positive
    private Long semesterId;

    @Schema(description = "课程 ID")
    @Positive
    private Long courseId;
}
