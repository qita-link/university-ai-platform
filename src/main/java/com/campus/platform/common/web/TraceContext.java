package com.campus.platform.common.web;

import org.slf4j.MDC;

/**
 * 请求追踪上下文。traceId 存放在 SLF4J MDC 中，日志、错误响应和审计记录共用同一个值。
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    public static final String MDC_USER_KEY = "userId";
    public static final String RESPONSE_HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }
}
