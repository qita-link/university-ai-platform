package com.campus.platform.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 业务指标（Prometheus 端点 {@code /actuator/prometheus}）。
 * <p>
 * 只统计“出问题时最想第一时间看到”的四个信号，HTTP 请求量/状态码由 Micrometer 的
 * {@code http.server.requests} 自动提供，不在这里重复埋点。
 */
@Component
public class PlatformMetrics {

    private final MeterRegistry registry;

    public PlatformMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 登录失败（含用户不存在、密码错误、账号不可用）。 */
    public void loginFailure(String reason) {
        Counter.builder("campus.auth.login.failure")
                .description("登录失败次数")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /** 连续失败触发临时锁定。 */
    public void loginLocked() {
        Counter.builder("campus.auth.login.locked")
                .description("账号被临时锁定次数")
                .register(registry)
                .increment();
    }

    /** AI 问答因未配置模型或模型调用失败返回 503。 */
    public void aiUnavailable() {
        Counter.builder("campus.ai.chat.unavailable")
                .description("AI 问答不可用次数")
                .register(registry)
                .increment();
    }

    /** AI 工具调用执行失败。 */
    public void aiToolFailed() {
        Counter.builder("campus.ai.tool.failed")
                .description("AI 工具调用失败次数")
                .register(registry)
                .increment();
    }
}
