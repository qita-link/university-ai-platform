package com.campus.platform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 密码强度配置（前缀 app.security.password）。
 *
 * @param minLength            最小长度（字符数）
 * @param minCharacterClasses  至少包含几类字符（小写字母、大写字母、数字、符号）
 */
@ConfigurationProperties(prefix = "app.security.password")
public record PasswordPolicyProperties(Integer minLength, Integer minCharacterClasses) {

    public PasswordPolicyProperties {
        if (minLength == null || minLength < 8) {
            minLength = 8;
        }
        if (minCharacterClasses == null || minCharacterClasses < 1) {
            minCharacterClasses = 3;
        }
        if (minCharacterClasses > 4) {
            minCharacterClasses = 4;
        }
    }
}
