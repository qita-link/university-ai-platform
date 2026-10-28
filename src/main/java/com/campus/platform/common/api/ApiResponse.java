package com.campus.platform.common.api;

import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.common.web.TraceContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 统一 API 响应体。
 * <p>
 * 成功与失败使用同一结构；HTTP 状态码表达大类语义，{@code code} 表达具体业务错误码。
 *
 * @param success   是否成功
 * @param code      业务码，成功为 {@code OK}，失败见 {@link ErrorCode}
 * @param message   面向用户的提示信息（不包含堆栈、SQL、密钥等内部信息）
 * @param data      业务数据
 * @param errors    字段级校验错误，仅参数校验失败时返回
 * @param traceId   请求追踪 ID，便于根据日志排查问题
 * @param timestamp 响应时间
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "统一响应体")
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        List<FieldError> errors,
        String traceId,
        OffsetDateTime timestamp) {

    public static final String OK = "OK";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, OK, "success", data, null, TraceContext.currentTraceId(), OffsetDateTime.now());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode, message, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, List<FieldError> errors) {
        String msg = (message == null || message.isBlank()) ? errorCode.defaultMessage() : message;
        return new ApiResponse<>(false, errorCode.name(), msg, null, errors, TraceContext.currentTraceId(),
                OffsetDateTime.now());
    }

    /**
     * 字段级错误。
     *
     * @param field   字段路径，如 {@code title}、{@code items[0].name}
     * @param message 错误描述
     */
    public record FieldError(String field, String message) {
    }
}
