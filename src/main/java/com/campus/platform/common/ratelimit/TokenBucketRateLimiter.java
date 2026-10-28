package com.campus.platform.common.ratelimit;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 进程内令牌桶限流器（单实例部署适用，不依赖 Redis）。
 * <p>
 * 每个键一个桶：容量 capacity，在 period 内匀速补满。桶补满后与新桶等价，可以安全回收，
 * 因此内存占用只与“最近 period 内活跃的键”数量有关。
 * <p>
 * 内存上限：跟踪的键超过 maxKeys 且回收后仍然超限时，<b>拒绝新键</b>（失败关闭）。
 * 这样攻击者无法通过构造大量不同键把限流器撑爆或使其失效；代价是在极端情况下新来源会被短暂限流。
 * <p>
 * 多实例部署时每个实例单独计数，整体阈值约为单实例阈值 × 实例数。
 */
public final class TokenBucketRateLimiter {

    private static final int SWEEP_EVERY_CALLS = 1024;
    private static final int SWEEP_MIN_SIZE = 1000;

    private final int capacity;
    private final double tokensPerNano;
    private final long fullRefillNanos;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger callsSinceSweep = new AtomicInteger();

    public TokenBucketRateLimiter(int capacity, Duration period, int maxKeys) {
        this(capacity, period, maxKeys, System::nanoTime);
    }

    /** 测试用：可注入单调时钟（纳秒）。 */
    public TokenBucketRateLimiter(int capacity, Duration period, int maxKeys, LongSupplier nanoClock) {
        if (capacity < 1 || period.isZero() || period.isNegative() || maxKeys < 1) {
            throw new IllegalArgumentException("限流参数不合法");
        }
        this.capacity = capacity;
        this.fullRefillNanos = period.toNanos();
        this.tokensPerNano = (double) capacity / fullRefillNanos;
        this.maxKeys = maxKeys;
        this.nanoClock = nanoClock;
    }

    /**
     * 尝试为 key 消耗一个令牌。
     *
     * @return 0 表示允许；大于 0 表示被限流，值为建议的重试等待秒数
     */
    public long tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        maybeSweep(now);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxKeys) {
                sweep(now);
                if (buckets.size() >= maxKeys) {
                    return Math.max(1, fullRefillNanos / 1_000_000_000L);
                }
            }
            bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        }
        synchronized (bucket) {
            bucket.refill(now, tokensPerNano, capacity);
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return 0;
            }
            double nanosUntilOne = (1.0 - bucket.tokens) / tokensPerNano;
            return Math.max(1, (long) Math.ceil(nanosUntilOne / 1_000_000_000.0));
        }
    }

    /** 当前跟踪的键数量（监控与测试用）。 */
    public int trackedKeys() {
        return buckets.size();
    }

    private void maybeSweep(long now) {
        if (callsSinceSweep.incrementAndGet() >= SWEEP_EVERY_CALLS) {
            callsSinceSweep.set(0);
            if (buckets.size() >= SWEEP_MIN_SIZE) {
                sweep(now);
            }
        }
    }

    /** 回收已补满的桶。并发下极少数情况会丢失一次扣减，只会让限流略微宽松，可接受。 */
    private void sweep(long now) {
        buckets.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            synchronized (b) {
                return b.tokens + (now - b.lastRefillNanos) * tokensPerNano >= capacity;
            }
        });
    }

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefillNanos = now;
        }

        void refill(long now, double tokensPerNano, int capacity) {
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
                lastRefillNanos = now;
            }
        }
    }
}
