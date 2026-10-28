package com.campus.platform.common.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * 通用分页查询参数（页码从 1 开始）。
 * <p>
 * 单页上限 {@value #MAX_SIZE}，防止一次性加载大量数据。各模块的查询 DTO 可继承本类追加过滤条件。
 */
@Getter
@Setter
@Schema(description = "分页参数")
public class PageQuery {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    @Schema(description = "页码，从 1 开始", defaultValue = "1")
    @Min(value = 1, message = "页码最小为 1")
    @Max(value = 100000, message = "页码过大")
    private int page = 1;

    @Schema(description = "每页条数，1~100", defaultValue = "20")
    @Min(value = 1, message = "每页条数最小为 1")
    @Max(value = MAX_SIZE, message = "每页条数最大为 " + MAX_SIZE)
    private int size = DEFAULT_SIZE;

    /** 转换为 MyBatis-Plus 分页对象。 */
    public <T> Page<T> toPage() {
        return Page.of(page, size);
    }
}
