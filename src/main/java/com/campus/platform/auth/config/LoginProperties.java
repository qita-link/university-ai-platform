package com.campus.platform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 登录保护配置（前缀 app.security.login）。
 *
 * @param maxFailedAttempts 连续失败多少次后临时锁定账号
 * @param lockDuration      临时锁定时长
 * @param rateLimit         进程内限流配置
 */
@ConfigurationProperties(prefix = "app.security.login")
public record LoginProperties(Integer maxFailedAttempts, Duration lockDuration, RateLimit rateLimit) {

    public LoginProperties {
        if (maxFailedAttempts == null || maxFailedAttempts < 1) {
            maxFailedAttempts = 5;
        }
        if (lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) {
            lockDuration = Duration.ofMinutes(15);
        }
        if (rateLimit == null) {
            rateLimit = new RateLimit(null, null, null, null);
        }
    }

    /**
     * 令牌桶限流。每个桶容量为 capacity，在 period 内匀速补满。
     *
     * @param enabled            是否启用
     * @param perIpAndUsername   同一 IP + 同一用户名
     * @param perIp              同一 IP（防止换用户名撞库）
     * @param maxTrackedKeys     内存中最多跟踪的键数量，防止被大量不同键撑爆内存
     */
    public record RateLimit(Boolean enabled, Bucket perIpAndUsername, Bucket perIp, Integer maxTrackedKeys) {
        public RateLimit {
            if (enabled == null) {
                enabled = true;
            }
            if (perIpAndUsername == null) {
                perIpAndUsername = new Bucket(10, Duration.ofMinutes(1));
            }
            if (perIp == null) {
                perIp = new Bucket(60, Duration.ofMinutes(1));
            }
            if (maxTrackedKeys == null || maxTrackedKeys < 100) {
                maxTrackedKeys = 100_000;
            }
        }
    }

    /**
     * @param capacity 桶容量（允许的突发请求数）
     * @param period   从空桶补满所需时间
     */
    public record Bucket(Integer capacity, Duration period) {
        public Bucket {
            if (capacity == null || capacity < 1) {
                throw new IllegalStateException("限流桶容量必须 >= 1");
            }
            if (period == null || period.isNegative() || period.isZero()) {
                throw new IllegalStateException("限流补满周期必须 > 0");
            }
        }
    }
}
