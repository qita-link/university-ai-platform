package com.campus.platform.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;

/**
 * 已认证请求的 Authentication 对象，principal 为 {@link AuthenticatedUser}。
 * 控制器可通过 {@code @AuthenticationPrincipal AuthenticatedUser user} 直接获取当前用户。
 */
public class UserAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser principal;
    private final transient Jwt jwt;

    public UserAuthenticationToken(AuthenticatedUser principal, Jwt jwt,
                                   Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return principal;
    }

    /** 不暴露原始令牌字符串，避免被误写入日志。 */
    @Override
    public Object getCredentials() {
        return null;
    }

    public Jwt getJwt() {
        return jwt;
    }

    @Override
    public String getName() {
        return principal.username();
    }
}
