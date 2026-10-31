package com.campus.platform.security.jwt;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    @Test
    void rejectsMissingSecret() {
        assertThatThrownBy(() -> new JwtProperties("", "iss", Duration.ofHours(1), Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
        assertThatThrownBy(() -> new JwtProperties(null, "iss", Duration.ofHours(1), Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtProperties("short-secret", "iss", Duration.ofHours(1), Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void appliesDefaultsAndHidesSecretInToString() {
        String secret = "0123456789abcdef0123456789abcdef";
        JwtProperties p = new JwtProperties(secret, null, null, null);
        assertThat(p.issuer()).isEqualTo("campus-ai-platform");
        assertThat(p.accessTokenTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(p.refreshTokenTtl()).isEqualTo(Duration.ofDays(14));
        assertThat(p.toString()).doesNotContain(secret);
    }

    @Test
    void rejectsRefreshTtlNotLongerThanAccessTtl() {
        String secret = "0123456789abcdef0123456789abcdef";
        assertThatThrownBy(() -> new JwtProperties(secret, "iss", Duration.ofDays(7), Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("刷新令牌");
        assertThatThrownBy(() -> new JwtProperties(secret, "iss", Duration.ofDays(7), Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
