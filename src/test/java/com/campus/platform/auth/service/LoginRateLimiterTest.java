package com.campus.platform.auth.service;

import com.campus.platform.auth.config.LoginProperties;
import com.campus.platform.common.exception.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    private static LoginRateLimiter limiter(int perIp, int perIpUser) {
        return new LoginRateLimiter(new LoginProperties(5, Duration.ofMinutes(15),
                new LoginProperties.RateLimit(true,
                        new LoginProperties.Bucket(perIpUser, Duration.ofMinutes(1)),
                        new LoginProperties.Bucket(perIp, Duration.ofMinutes(1)),
                        1000)));
    }

    @Test
    void perIpAndUsernameIsCaseInsensitive() {
        LoginRateLimiter l = limiter(100, 2);
        l.checkLogin("1.1.1.1", "Alice");
        l.checkLogin("1.1.1.1", "alice ");
        // 变换大小写不能绕过限流
        assertThatThrownBy(() -> l.checkLogin("1.1.1.1", "ALICE")).isInstanceOf(RateLimitedException.class);
        // 其他 IP、其他用户名不受影响
        assertThatCode(() -> l.checkLogin("2.2.2.2", "alice")).doesNotThrowAnyException();
        assertThatCode(() -> l.checkLogin("1.1.1.1", "bob")).doesNotThrowAnyException();
    }

    @Test
    void perIpLimitStopsUsernameSpraying() {
        LoginRateLimiter l = limiter(3, 100);
        l.checkLogin("9.9.9.9", "u1");
        l.checkLogin("9.9.9.9", "u2");
        l.checkLogin("9.9.9.9", "u3");
        assertThatThrownBy(() -> l.checkLogin("9.9.9.9", "u4"))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(((RateLimitedException) e).getRetryAfterSeconds()).isPositive());
    }

    @Test
    void canBeDisabled() {
        LoginRateLimiter l = new LoginRateLimiter(new LoginProperties(5, Duration.ofMinutes(15),
                new LoginProperties.RateLimit(false, new LoginProperties.Bucket(1, Duration.ofMinutes(1)),
                        new LoginProperties.Bucket(1, Duration.ofMinutes(1)), 1000)));
        for (int i = 0; i < 10; i++) {
            l.checkLogin("1.1.1.1", "x");
            l.checkPasswordChange(1L);
        }
    }
}
