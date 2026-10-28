package com.campus.platform.auth.service;

import com.campus.platform.auth.config.LoginProperties;
import com.campus.platform.common.exception.RateLimitedException;
import com.campus.platform.common.ratelimit.TokenBucketRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 登录相关接口的进程内限流。
 * <ul>
 *   <li>登录：同时检查“IP”与“IP + 用户名”两个桶，前者限制换用户名撞库，后者限制针对单个账号的猜测；</li>
 *   <li>修改密码：按用户 ID 限制原密码猜测（持有他人令牌时的暴力尝试）。</li>
 * </ul>
 * 用户名统一转小写后作为键：数据库排序规则不区分大小写，若键区分大小写，攻击者可以通过变换大小写绕过限流。
 * <p>
 * 被限流的请求不写审计（否则攻击流量会刷爆审计表），只写一行日志。
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private final boolean enabled;
    private final TokenBucketRateLimiter perIp;
    private final TokenBucketRateLimiter perIpAndUsername;
    private final TokenBucketRateLimiter perUserPasswordChange;

    public LoginRateLimiter(LoginProperties properties) {
        LoginProperties.RateLimit cfg = properties.rateLimit();
        this.enabled = cfg.enabled();
        this.perIp = new TokenBucketRateLimiter(cfg.perIp().capacity(), cfg.perIp().period(), cfg.maxTrackedKeys());
        this.perIpAndUsername = new TokenBucketRateLimiter(cfg.perIpAndUsername().capacity(),
                cfg.perIpAndUsername().period(), cfg.maxTrackedKeys());
        this.perUserPasswordChange = new TokenBucketRateLimiter(cfg.perIpAndUsername().capacity(),
                cfg.perIpAndUsername().period(), cfg.maxTrackedKeys());
    }

    /** 登录前调用，超限时抛出 {@link RateLimitedException}。 */
    public void checkLogin(String clientIp, String username) {
        if (!enabled) {
            return;
        }
        String ip = clientIp == null ? "unknown" : clientIp;
        long wait = perIp.tryAcquire(ip);
        if (wait == 0) {
            wait = perIpAndUsername.tryAcquire(ip + '|' + normalize(username));
        }
        if (wait > 0) {
            log.warn("登录请求被限流: ip={}, retryAfter={}s", ip, wait);
            throw new RateLimitedException(wait);
        }
    }

    /** 修改本人密码前调用。 */
    public void checkPasswordChange(long userId) {
        if (!enabled) {
            return;
        }
        long wait = perUserPasswordChange.tryAcquire(Long.toString(userId));
        if (wait > 0) {
            log.warn("修改密码请求被限流: userId={}, retryAfter={}s", userId, wait);
            throw new RateLimitedException(wait);
        }
    }

    static String normalize(String username) {
        if (username == null) {
            return "";
        }
        String s = username.trim().toLowerCase(Locale.ROOT);
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
