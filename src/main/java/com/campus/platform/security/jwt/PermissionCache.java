package com.campus.platform.security.jwt;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 角色/权限快照缓存。
 * <p>
 * 鉴权时每个请求都要拿角色与权限点，原来是 3 次查询（用户行 + 角色 + 权限）。
 * 高并发下这部分会把连接池打满，因此对「角色 + 权限」做短时缓存，
 * 用户行仍然每次实时查询——账号停用、锁定、token_version 变化必须立即生效。
 * <p>
 * 缓存条目里带着载入时的 token_version：改密、退出、重置密码会让 token_version 自增，
 * 旧条目自然失配并重新加载，不需要显式失效。角色调整最多延迟一个 TTL 生效。
 */
@Component
public class PermissionCache {

    private final Cache<Long, Snapshot> cache;

    public PermissionCache(@Value("${app.security.permission-cache-ttl:30s}") Duration ttl,
                           @Value("${app.security.permission-cache-max:10000}") long maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .build();
    }

    /**
     * 取快照；token_version 不一致或已过期时重新加载。
     *
     * @param userId       用户 ID
     * @param tokenVersion 令牌里携带的版本号
     * @param loader       缓存未命中时的加载逻辑
     */
    public Snapshot get(long userId, int tokenVersion, Supplier<Snapshot> loader) {
        Snapshot cached = cache.getIfPresent(userId);
        if (cached != null && cached.tokenVersion() == tokenVersion) {
            return cached;
        }
        Snapshot loaded = loader.get();
        cache.put(userId, loaded);
        return loaded;
    }

    /** 用户被删除或需要强制重新加载时调用。 */
    public void invalidate(long userId) {
        cache.invalidate(userId);
    }

    /** 角色 + 权限点快照，附带载入时的 token_version。 */
    public record Snapshot(int tokenVersion, List<String> roles, List<String> permissions) {
    }
}
