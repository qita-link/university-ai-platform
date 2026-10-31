package com.campus.platform.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.auth.config.LoginProperties;
import com.campus.platform.auth.dto.CurrentUserResponse;
import com.campus.platform.auth.dto.LoginResponse;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.common.metrics.PlatformMetrics;
import com.campus.platform.common.web.ClientInfo;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.jwt.JwtTokenService;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 登录、退出、修改本人密码。
 * <p>
 * <b>登录失败锁定</b>：连续失败 maxFailedAttempts 次后设置 locked_until = now + lockDuration。
 * 锁定期间不校验密码（否则攻击者可以借“锁定 / 密码错误”两种不同响应继续猜密码），直接返回 AUTH_ACCOUNT_LOCKED。
 * 锁定到期后，下一次失败从 1 重新计数；任意一次成功登录清零。临时锁定只阻止新登录，不吊销已签发的令牌，
 * 以免攻击者通过故意输错密码把正在使用的合法用户踢下线。
 * <p>
 * <b>防账号枚举</b>：用户名不存在时同样执行一次 bcrypt 校验（对一个启动时生成的随机哈希），
 * 使响应耗时与“用户存在但密码错误”相近；两种情况返回同一错误码和提示。
 * 账号停用 / 被管理员锁定的提示只在密码正确之后才返回。
 * <p>
 * <b>并发</b>：bcrypt 校验在事务外进行（耗时约 100ms，不占用行锁）；计数更新在事务内对用户行加锁后执行，
 * 并在锁内重新检查锁定状态，因此并发的失败请求不会丢失计数，并发的正确请求也不会越过刚刚生效的锁定。
 */
@Slf4j
@Service
public class AuthService {

    static final String MODULE = "auth";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final LoginRateLimiter rateLimiter;
    private final PasswordPolicy passwordPolicy;
    private final LoginProperties loginProperties;
    private final AuditService auditService;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JwtDecoder jwtDecoder;
    private final PlatformMetrics metrics;
    /** 用户不存在时用于等时比较的哈希 */
    private final String dummyHash;

    public AuthService(SysUserMapper userMapper, PasswordEncoder passwordEncoder, JwtTokenService tokenService,
                       LoginRateLimiter rateLimiter, PasswordPolicy passwordPolicy, LoginProperties loginProperties,
                       AuditService auditService, TransactionTemplate tx, Clock clock,
                       JwtDecoder jwtDecoder, PlatformMetrics metrics) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.passwordPolicy = passwordPolicy;
        this.loginProperties = loginProperties;
        this.auditService = auditService;
        this.tx = tx;
        this.clock = clock;
        this.jwtDecoder = jwtDecoder;
        this.metrics = metrics;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // ======================================================================
    // 登录
    // ======================================================================

    public LoginResponse login(String rawUsername, String password) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        rateLimiter.checkLogin(ClientInfo.current().ip(), username);

        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            auditLoginFailure(null, username, ErrorCode.AUTH_BAD_CREDENTIALS, "USER_NOT_FOUND");
            metrics.loginFailure("USER_NOT_FOUND");
            throw new BusinessException(ErrorCode.AUTH_BAD_CREDENTIALS);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (isTemporarilyLocked(user, now)) {
            passwordEncoder.matches(password, dummyHash);
            auditLoginFailure(user.getId(), user.getUsername(), ErrorCode.AUTH_ACCOUNT_LOCKED, "TEMPORARILY_LOCKED");
            metrics.loginFailure("TEMPORARILY_LOCKED");
            throw lockedException(user.getLockedUntil(), now);
        }

        boolean passwordOk = passwordEncoder.matches(password, user.getPasswordHash());
        if (!passwordOk) {
            onPasswordMismatch(user.getId(), user.getUsername());
            throw new BusinessException(ErrorCode.AUTH_BAD_CREDENTIALS);
        }

        // 密码正确：在行锁内复核状态与锁定（防止与并发失败请求竞争），然后清零计数
        SysUser fresh = tx.execute(status -> {
            SysUser locked = userMapper.selectByIdForUpdate(user.getId());
            LocalDateTime t = LocalDateTime.now(clock);
            if (locked.getStatus() != UserStatus.ACTIVE || isTemporarilyLocked(locked, t)) {
                return locked;
            }
            userMapper.updateLoginSuccess(locked.getId(), t);
            return locked;
        });
        LocalDateTime t = LocalDateTime.now(clock);
        if (isTemporarilyLocked(fresh, t)) {
            auditLoginFailure(fresh.getId(), fresh.getUsername(), ErrorCode.AUTH_ACCOUNT_LOCKED, "TEMPORARILY_LOCKED");
            throw lockedException(fresh.getLockedUntil(), t);
        }
        if (fresh.getStatus() == UserStatus.DISABLED) {
            auditLoginFailure(fresh.getId(), fresh.getUsername(), ErrorCode.AUTH_ACCOUNT_DISABLED, "DISABLED");
            metrics.loginFailure("DISABLED");
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (fresh.getStatus() == UserStatus.LOCKED) {
            auditLoginFailure(fresh.getId(), fresh.getUsername(), ErrorCode.AUTH_ACCOUNT_LOCKED, "ADMIN_LOCKED");
            metrics.loginFailure("ADMIN_LOCKED");
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED, "账号已被管理员锁定，请联系管理员");
        }

        upgradeHashIfNeeded(fresh, password);

        auditService.record(AuditEvent.builder(MODULE, "LOGIN")
                .actor(fresh.getId(), fresh.getUsername())
                .target("USER", fresh.getId())
                .build());
        return issue(fresh);
    }

    private void onPasswordMismatch(long userId, String username) {
        int max = loginProperties.maxFailedAttempts();
        Duration lockDuration = loginProperties.lockDuration();
        LockOutcome outcome = tx.execute(status -> {
            SysUser locked = userMapper.selectByIdForUpdate(userId);
            LocalDateTime now = LocalDateTime.now(clock);
            if (isTemporarilyLocked(locked, now)) {
                // 并发请求已经触发锁定，不再累加
                return new LockOutcome(locked.getFailedLoginCount(), false);
            }
            // 上一次锁定已到期：重新计数
            int base = locked.getLockedUntil() != null ? 0 : locked.getFailedLoginCount();
            int count = base + 1;
            if (count >= max) {
                userMapper.updateLoginFailure(userId, count, now.plus(lockDuration));
                return new LockOutcome(count, true);
            }
            userMapper.updateLoginFailure(userId, count, null);
            return new LockOutcome(count, false);
        });
        auditLoginFailure(userId, username, ErrorCode.AUTH_BAD_CREDENTIALS, "BAD_PASSWORD",
                "failedCount", outcome.failedCount());
        metrics.loginFailure("BAD_PASSWORD");
        if (outcome.lockedNow()) {
            metrics.loginLocked();
            log.warn("账号连续登录失败已临时锁定: userId={}, minutes={}", userId, lockDuration.toMinutes());
            auditService.record(AuditEvent.builder(MODULE, "ACCOUNT_TEMP_LOCKED")
                    .actor(userId, username)
                    .target("USER", userId)
                    .detail("failedCount", outcome.failedCount())
                    .detail("lockMinutes", lockDuration.toMinutes())
                    .build());
        }
    }

    private record LockOutcome(int failedCount, boolean lockedNow) {
    }

    private void upgradeHashIfNeeded(SysUser user, String password) {
        try {
            if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
                userMapper.rehashPassword(user.getId(), user.getPasswordHash(), passwordEncoder.encode(password));
            }
        } catch (RuntimeException ex) {
            // 升级失败不影响登录
            log.warn("密码哈希升级失败: userId={}", user.getId(), ex);
        }
    }

    // ======================================================================
    // 退出与修改密码
    // ======================================================================

    /**
     * 退出登录：token_version 自增。由于令牌是无状态的，这会使该用户在所有设备上的令牌同时失效。
     */
    public void logout(AuthenticatedUser user) {
        userMapper.incrementTokenVersion(user.userId());
        auditService.record(AuditEvent.builder(MODULE, "LOGOUT").target("USER", user.userId()).build());
    }

    /**
     * 修改本人密码。成功后其他设备上的令牌全部失效，返回给当前客户端一个新令牌。
     */
    public LoginResponse changePassword(AuthenticatedUser current, String oldPassword, String newPassword) {
        rateLimiter.checkPasswordChange(current.userId());
        SysUser user = userMapper.selectById(current.userId());
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            auditService.record(AuditEvent.builder(MODULE, "PASSWORD_CHANGE")
                    .target("USER", user.getId())
                    .failure(ErrorCode.AUTH_PASSWORD_MISMATCH.name())
                    .build());
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_MISMATCH);
        }
        passwordPolicy.validate(newPassword, user.getUsername());
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_WEAK, "新密码不能与原密码相同");
        }
        int updated = userMapper.updatePassword(user.getId(), passwordEncoder.encode(newPassword),
                LocalDateTime.now(clock), user.getPasswordHash(), user.getId());
        if (updated != 1) {
            throw BusinessException.conflict("密码已被修改，请重新登录后再试");
        }
        auditService.record(AuditEvent.builder(MODULE, "PASSWORD_CHANGE").target("USER", user.getId()).build());
        return issue(userMapper.selectById(user.getId()));
    }

    // ======================================================================
    // helpers
    // ======================================================================

    private LoginResponse issue(SysUser user) {
        JwtTokenService.IssuedTokens tokens = tokenService.issueTokens(user.getId(), user.getUsername(),
                user.getTokenVersion());
        CurrentUserResponse me = new CurrentUserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getAvatarVersion() == null ? 0 : user.getAvatarVersion(), user.getOrgUnitId(),
                new TreeSet<>(userMapper.selectRoleCodes(user.getId())),
                new TreeSet<>(userMapper.selectPermissionCodes(user.getId())));
        return new LoginResponse(tokens.access().accessToken(), tokens.refresh().accessToken(), LoginResponse.BEARER,
                tokens.access().expiresInSeconds(), tokens.access().expiresAt(), tokens.refresh().expiresAt(), me);
    }

    // ======================================================================
    // 刷新令牌
    // ======================================================================

    /**
     * 用刷新令牌换一对新令牌（访问令牌 + 轮换后的刷新令牌）。
     * <p>
     * 任何失败都统一返回 401，不区分“签名不对 / 类型不对 / 用户不存在 / 版本过期”，避免被用来探测账号。
     * 账号状态与 token_version 在这里同样复核：退出登录、改密、停用都会让刷新令牌立即失效。
     */
    public LoginResponse refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(rawRefreshToken);
        } catch (RuntimeException ex) {
            // 过期、签名错误、格式错误都走这里
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!JwtTokenService.TOKEN_TYPE_REFRESH.equals(jwt.getClaimAsString(JwtTokenService.CLAIM_TOKEN_TYPE))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Object ver = jwt.getClaim(JwtTokenService.CLAIM_TOKEN_VERSION);
        if (!(ver instanceof Number n) || !java.util.Objects.equals(n.intValue(), user.getTokenVersion())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return issue(user);
    }

    private static boolean isTemporarilyLocked(SysUser user, LocalDateTime now) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
    }

    private static BusinessException lockedException(LocalDateTime until, LocalDateTime now) {
        long minutes = Math.max(1, (Duration.between(now, until).getSeconds() + 59) / 60);
        return new BusinessException(ErrorCode.AUTH_ACCOUNT_LOCKED,
                "连续登录失败次数过多，账号已临时锁定，请 " + minutes + " 分钟后再试");
    }

    private void auditLoginFailure(Long userId, String username, ErrorCode code, String reason, Object... extra) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, "LOGIN")
                .actor(userId, truncate(username))
                .target("USER", userId)
                .failure(code.name())
                .detail("reason", reason);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            b.detail(String.valueOf(extra[i]), extra[i + 1]);
        }
        auditService.record(b.build());
    }

    private static String truncate(String s) {
        return s != null && s.length() > 64 ? s.substring(0, 64) : s;
    }
}
