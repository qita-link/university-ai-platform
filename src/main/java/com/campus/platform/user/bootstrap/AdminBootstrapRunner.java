package com.campus.platform.user.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.audit.entity.AuditSource;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.security.Roles;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 启动时引导创建初始管理员。
 * <ul>
 *   <li>已存在 ACTIVE 管理员：不做任何事；</li>
 *   <li>未提供 APP_BOOTSTRAP_ADMIN_PASSWORD：输出警告并跳过（不会生成默认密码）；</li>
 *   <li>同名用户已存在但不是管理员：拒绝自动提升权限，输出警告，需人工处理。</li>
 * </ul>
 */
@Slf4j
@Component
@Order(0)
public class AdminBootstrapRunner implements ApplicationRunner {

    private final AdminBootstrapProperties properties;
    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final AuditService auditService;
    private final Clock clock;

    public AdminBootstrapRunner(AdminBootstrapProperties properties, SysUserMapper userMapper,
                                PasswordEncoder passwordEncoder, TransactionTemplate tx,
                                AuditService auditService, Clock clock) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tx = tx;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userMapper.countActiveUsersWithRole(Roles.ADMIN) > 0) {
            return;
        }
        if (!properties.passwordProvided()) {
            log.warn("系统中没有可用的管理员账号。请设置 APP_BOOTSTRAP_ADMIN_USERNAME / APP_BOOTSTRAP_ADMIN_PASSWORD 后重启以创建初始管理员。");
            return;
        }
        if (properties.password().length() < AdminBootstrapProperties.MIN_PASSWORD_LENGTH) {
            log.error("APP_BOOTSTRAP_ADMIN_PASSWORD 长度不足 {} 位，已跳过初始管理员创建", AdminBootstrapProperties.MIN_PASSWORD_LENGTH);
            return;
        }
        SysUser existing = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, properties.username()));
        if (existing != null) {
            log.warn("用户名 {} 已被非管理员账号占用，出于安全考虑不会自动提升权限，请更换 APP_BOOTSTRAP_ADMIN_USERNAME",
                    properties.username());
            return;
        }

        Long userId = tx.execute(status -> {
            SysUser admin = new SysUser();
            admin.setUsername(properties.username());
            admin.setPasswordHash(passwordEncoder.encode(properties.password()));
            admin.setDisplayName(properties.displayName());
            admin.setStatus(UserStatus.ACTIVE);
            admin.setTokenVersion(0);
            admin.setFailedLoginCount(0);
            admin.setPasswordChangedAt(LocalDateTime.now(clock));
            userMapper.insert(admin);
            userMapper.grantRole(admin.getId(), Roles.ADMIN);
            return admin.getId();
        });

        auditService.record(AuditEvent.builder("user", "ADMIN_BOOTSTRAP")
                .source(AuditSource.SYSTEM)
                .target("USER", userId)
                .detail("username", properties.username())
                .build());
        log.info("已创建初始管理员账号: username={}, id={}。请登录后尽快修改密码，并从环境变量中移除初始密码。",
                properties.username(), userId);
    }
}
