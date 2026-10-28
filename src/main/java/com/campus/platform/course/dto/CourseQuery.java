package com.campus.platform.course.dto;

import com.campus.platform.common.api.PageQuery;
import com.campus.platform.course.entity.CourseStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "课程查询")
public class CourseQuery extends PageQuery {

    @Schema(description = "学期 ID；为空时查询全部学期")
    @Positive
    private Long semesterId;

    @Schema(description = "课程编码或名称关键词")
    @Size(max = 100)
    private String keyword;

    @Schema(description = "课程状态；为空时排除已停开课程")
    private CourseStatus status;

    @Schema(description = "授课教师 ID（主讲或助教）")
    @Positive
    private Long teacherId;

    @Schema(description = "开课组织 ID")
    @Positive
    private Long orgUnitId;
}
