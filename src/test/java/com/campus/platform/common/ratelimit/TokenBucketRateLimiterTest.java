package com.campus.platform.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void allowsBurstUpToCapacityThenRejectsWithRetryAfter() {
        AtomicLong now = new AtomicLong(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, Duration.ofSeconds(60), 100, now::get);
        assertThat(limiter.tryAcquire("k")).isZero();
        assertThat(limiter.tryAcquire("k")).isZero();
        assertThat(limiter.tryAcquire("k")).isZero();
        // 3 个令牌 / 60 秒 => 每 20 秒补 1 个
        assertThat(limiter.tryAcquire("k")).isEqualTo(20);
        // 其他键不受影响
        assertThat(limiter.tryAcquire("other")).isZero();
    }

    @Test
    void refillsOverTime() {
        AtomicLong now = new AtomicLong(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, Duration.ofSeconds(10), 100, now::get);
        limiter.tryAcquire("k");
        limiter.tryAcquire("k");
        assertThat(limiter.tryAcquire("k")).isPositive();
        now.addAndGet(5 * SECOND); // 补 1 个
        assertThat(limiter.tryAcquire("k")).isZero();
        assertThat(limiter.tryAcquire("k")).isPositive();
        now.addAndGet(100 * SECOND); // 补满但不超过容量
        assertThat(limiter.tryAcquire("k")).isZero();
        assertThat(limiter.tryAcquire("k")).isZero();
        assertThat(limiter.tryAcquire("k")).isPositive();
    }

    @Test
    void failsClosedWhenTooManyKeysAndReclaimsFullBuckets() {
        AtomicLong now = new AtomicLong(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, Duration.ofSeconds(10), 3, now::get);
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("b")).isZero();
        assertThat(limiter.tryAcquire("c")).isZero();
        // 键数已满且没有可回收的桶：新键被拒绝（失败关闭），已有键照常计数
        assertThat(limiter.tryAcquire("d")).isPositive();
        assertThat(limiter.trackedKeys()).isEqualTo(3);
        // 桶补满后可以回收，新键随之可用
        now.addAndGet(11 * SECOND);
        assertThat(limiter.tryAcquire("d")).isZero();
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void concurrentAcquiresNeverExceedCapacity() throws Exception {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(50, Duration.ofHours(1), 100);
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 400; i++) {
            pool.submit(() -> {
                if (limiter.tryAcquire("shared") == 0) {
                    allowed.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        // 1 小时补 50 个，测试期间最多多补 1 个
        assertThat(allowed.get()).isBetween(50, 51);
    }
}
