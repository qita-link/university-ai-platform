package com.campus.platform.common.exception;

/**
 * 请求被限流（HTTP 429）。全局异常处理器会据此写出 {@code Retry-After} 响应头。
 */
public class RateLimitedException extends BusinessException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(ErrorCode.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
