package com.campus.platform.security.jwt;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 令牌签发：访问令牌（access）与刷新令牌（refresh）。
 * <p>
 * 令牌只携带用户 ID、用户名、token_version 和类型，不携带角色/权限：
 * 每个请求都会从数据库加载最新的角色、权限和账号状态（见 {@code JwtUserAuthenticationConverter}），
 * 因此停用账号、调整角色、修改密码（token_version 自增）都会立即使旧令牌失效。
 * <p>
 * 刷新令牌带 {@code typ=refresh}，{@code JwtUserAuthenticationConverter} 会拒绝把它当作访问令牌使用；
 * 它只能在 {@code POST /api/v1/auth/refresh} 上换取新的访问令牌。
 */
@Service
public class JwtTokenService {

    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_TOKEN_VERSION = "ver";
    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder jwtEncoder, JwtProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issueAccessToken(long userId, String username, int tokenVersion) {
        return issue(userId, username, tokenVersion, TOKEN_TYPE_ACCESS, properties.accessTokenTtl());
    }

    public IssuedToken issueRefreshToken(long userId, String username, int tokenVersion) {
        return issue(userId, username, tokenVersion, TOKEN_TYPE_REFRESH, properties.refreshTokenTtl());
    }

    /** 一次签发访问令牌与刷新令牌。 */
    public IssuedTokens issueTokens(long userId, String username, int tokenVersion) {
        return new IssuedTokens(issueAccessToken(userId, username, tokenVersion),
                issueRefreshToken(userId, username, tokenVersion));
    }

    private IssuedToken issue(long userId, String username, int tokenVersion, String type, java.time.Duration ttl) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(userId))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .claim(CLAIM_TOKEN_TYPE, type)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt, ttl.toSeconds());
    }

    /**
     * @param accessToken      令牌字符串
     * @param expiresAt        过期时间
     * @param expiresInSeconds 有效秒数
     */
    public record IssuedToken(String accessToken, Instant expiresAt, long expiresInSeconds) {

        /** 令牌不出现在日志中 */
        @Override
        public String toString() {
            return "IssuedToken[token=****, expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * @param access  访问令牌
     * @param refresh 刷新令牌
     */
    public record IssuedTokens(IssuedToken access, IssuedToken refresh) {
    }
}
