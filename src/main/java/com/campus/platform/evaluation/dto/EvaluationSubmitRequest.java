package com.campus.platform.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "提交评教")
public record EvaluationSubmitRequest(
        @NotNull(message = "课程不能为空") Long courseId,
        @NotNull(message = "教师不能为空") Long teacherId,
        @NotNull(message = "请评价教学态度") @Min(value = 1, message = "评分范围 1~5") @Max(value = 5, message = "评分范围 1~5")
        Integer scoreTeaching,
        @NotNull(message = "请评价课程内容") @Min(value = 1, message = "评分范围 1~5") @Max(value = 5, message = "评分范围 1~5")
        Integer scoreContent,
        @NotNull(message = "请评价互动答疑") @Min(value = 1, message = "评分范围 1~5") @Max(value = 5, message = "评分范围 1~5")
        Integer scoreInteraction,
        @NotNull(message = "请评价作业考核") @Min(value = 1, message = "评分范围 1~5") @Max(value = 5, message = "评分范围 1~5")
        Integer scoreWorkload,
        @Size(max = 500, message = "留言最多 500 字") String comment,
        @Schema(description = "是否匿名，缺省取平台设置") Boolean anonymous) {
}
