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
 * 访问令牌签发。
 * <p>
 * 令牌只携带用户 ID、用户名和 token_version，不携带角色/权限：
 * 每个请求都会从数据库加载最新的角色、权限和账号状态（见 {@code JwtUserAuthenticationConverter}），
 * 因此停用账号、调整角色、修改密码（token_version 自增）都会立即使旧令牌失效。
 */
@Service
public class JwtTokenService {

    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_TOKEN_VERSION = "ver";
    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String TOKEN_TYPE_ACCESS = "access";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder jwtEncoder, JwtProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issueAccessToken(long userId, String username, int tokenVersion) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(userId))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt, properties.accessTokenTtl().toSeconds());
    }

    /**
     * @param accessToken      令牌字符串
     * @param expiresAt        过期时间
     * @param expiresInSeconds 有效秒数
     */
    public record IssuedToken(String accessToken, Instant expiresAt, long expiresInSeconds) {
    }
}
