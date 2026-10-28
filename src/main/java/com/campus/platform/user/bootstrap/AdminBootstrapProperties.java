package com.campus.platform.user.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 初始管理员引导配置（前缀 app.bootstrap.admin）。
 * <p>
 * 仅当系统中不存在任何 ACTIVE 状态的管理员时才会创建账号；
 * 密码只通过环境变量 APP_BOOTSTRAP_ADMIN_PASSWORD 提供，首次启动成功后应从环境中移除。
 *
 * @param username    登录名
 * @param password    初始密码（至少 12 位）
 * @param displayName 显示名
 */
@ConfigurationProperties(prefix = "app.bootstrap.admin")
public record AdminBootstrapProperties(String username, String password, String displayName) {

    public static final int MIN_PASSWORD_LENGTH = 12;

    public AdminBootstrapProperties {
        if (username == null || username.isBlank()) {
            username = "admin";
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = "系统管理员";
        }
    }

    public boolean passwordProvided() {
        return password != null && !password.isBlank();
    }

    @Override
    public String toString() {
        return "AdminBootstrapProperties[username=" + username + ", password=****]";
    }
}
