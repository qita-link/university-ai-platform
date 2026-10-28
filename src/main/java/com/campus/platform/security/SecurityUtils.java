package com.campus.platform.security;

import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 获取当前登录用户的工具方法。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<AuthenticatedUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /** 获取当前用户，未登录时抛出 UNAUTHORIZED。 */
    public static AuthenticatedUser requireCurrentUser() {
        return currentUser().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    public static Long currentUserIdOrNull() {
        return currentUser().map(AuthenticatedUser::userId).orElse(null);
    }
}
