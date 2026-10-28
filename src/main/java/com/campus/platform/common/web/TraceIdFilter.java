package com.campus.platform.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 为每个请求生成 traceId，写入 MDC 和响应头，并在请求结束时输出一行访问日志。
 * <p>
 * 允许调用方（如网关）通过 {@code X-Request-Id} 传入追踪 ID，但只接受安全字符，防止日志注入。
 * 访问日志不记录查询串和请求体，避免泄露 Token、密码或个人信息。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    static final String REQUEST_HEADER = "X-Request-Id";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_HEADER);
        String traceId = (incoming != null && SAFE_ID.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString().replace("-", "");
        long start = System.nanoTime();
        MDC.put(TraceContext.MDC_KEY, traceId);
        response.setHeader(TraceContext.RESPONSE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            if (!request.getRequestURI().startsWith("/actuator")) {
                log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), costMs);
            }
            MDC.remove(TraceContext.MDC_KEY);
            MDC.remove(TraceContext.MDC_USER_KEY);
        }
    }
}
