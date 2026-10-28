package com.campus.platform.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JWT 配置（前缀 app.security.jwt）。
 *
 * @param secret         HS256 签名密钥，至少 32 字节；必须通过环境变量 APP_JWT_SECRET 提供，禁止写入代码或 Git
 * @param issuer         签发者
 * @param accessTokenTtl 访问令牌有效期
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenTtl) {

    public static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "未配置 JWT 签名密钥：请设置环境变量 APP_JWT_SECRET（至少 32 字节随机字符串，如 openssl rand -base64 48）");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT 签名密钥长度不足 " + MIN_SECRET_BYTES + " 字节");
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "campus-ai-platform";
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            accessTokenTtl = Duration.ofHours(2);
        }
    }

    /** 避免密钥出现在日志或调试输出中。 */
    @Override
    public String toString() {
        return "JwtProperties[secret=****, issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
}
