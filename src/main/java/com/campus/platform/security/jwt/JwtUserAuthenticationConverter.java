package com.campus.platform.security.jwt;

import com.campus.platform.common.web.TraceContext;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Roles;
import com.campus.platform.security.UserAuthenticationToken;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 把已通过签名/有效期校验的 JWT 转换为 {@link UserAuthenticationToken}。
 * <p>
 * 每个请求都会从数据库读取用户的状态、token_version、角色与权限：
 * 账号被停用/锁定、令牌版本不一致（改密或强制下线）时拒绝请求（401）。
 * 第一版直接查库（两条轻量查询），如有性能需要再加入短时缓存。
 */
@Slf4j
@Component
public class JwtUserAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final SysUserMapper userMapper;
    private final PermissionCache permissionCache;

    public JwtUserAuthenticationConverter(SysUserMapper userMapper, PermissionCache permissionCache) {
        this.userMapper = userMapper;
        this.permissionCache = permissionCache;
    }

    @Override
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        if (!JwtTokenService.TOKEN_TYPE_ACCESS.equals(jwt.getClaimAsString(JwtTokenService.CLAIM_TOKEN_TYPE))) {
            throw new InvalidBearerTokenException("令牌类型错误");
        }
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw new InvalidBearerTokenException("令牌主体无效");
        }

        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new InvalidBearerTokenException("用户不存在");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            log.info("拒绝非活动账号的令牌: userId={}, status={}", userId, user.getStatus());
            throw new InvalidBearerTokenException("账号不可用");
        }
        Object ver = jwt.getClaim(JwtTokenService.CLAIM_TOKEN_VERSION);
        if (!(ver instanceof Number n) || !Objects.equals(n.intValue(), user.getTokenVersion())) {
            throw new InvalidBearerTokenException("令牌已失效");
        }

        // 角色与权限走短时缓存（高并发下避免每请求 3 次查询）；用户行仍实时查，保证停用/锁定立即生效
        PermissionCache.Snapshot snapshot = permissionCache.get(userId, user.getTokenVersion(), () ->
                new PermissionCache.Snapshot(user.getTokenVersion(),
                        userMapper.selectRoleCodes(userId),
                        userMapper.selectPermissionCodes(userId)));
        List<String> roles = snapshot.roles();
        List<String> permissions = snapshot.permissions();
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getOrgUnitId(), new HashSet<>(roles), new HashSet<>(permissions));

        List<GrantedAuthority> authorities = new ArrayList<>(roles.size() + permissions.size());
        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority(Roles.ROLE_PREFIX + r)));
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));

        MDC.put(TraceContext.MDC_USER_KEY, String.valueOf(userId));
        return new UserAuthenticationToken(principal, jwt, authorities);
    }
}
