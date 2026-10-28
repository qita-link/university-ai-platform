package com.campus.platform.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.function.Function;

/**
 * 分页结果。
 *
 * @param items 当前页数据
 * @param total 总条数
 * @param page  当前页码（从 1 开始）
 * @param size  每页条数
 * @param pages 总页数
 */
@Schema(description = "分页结果")
public record PageResult<T>(List<T> items, long total, long page, long size, long pages) {

    /** 由 MyBatis-Plus 分页结果转换，同时把实体映射为 DTO（接口不直接返回实体）。 */
    public static <E, T> PageResult<T> of(IPage<E> page, Function<? super E, ? extends T> mapper) {
        List<T> items = page.getRecords().stream().<T>map(mapper).toList();
        return new PageResult<>(items, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(List.of(), 0, page, size, 0);
    }
}
