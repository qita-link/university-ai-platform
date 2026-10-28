package com.campus.platform.auth.service;

import com.campus.platform.auth.config.PasswordPolicyProperties;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 密码强度校验。用于修改本人密码、管理员创建用户与重置密码。
 * <ul>
 *   <li>长度：不少于 minLength 个字符，不超过 {@value #MAX_LENGTH} 个字符，且 UTF-8 编码不超过
 *       {@value #MAX_BYTES} 字节（bcrypt 只使用前 72 字节，Spring Security 对超长新密码直接抛异常）；</li>
 *   <li>字符类别：小写字母、大写字母、数字、其他符号中至少 minCharacterClasses 类；</li>
 *   <li>不能包含用户名（忽略大小写），不能含控制字符。</li>
 * </ul>
 * 失败信息只描述规则，不回显密码。
 */
@Component
public class PasswordPolicy {

    public static final int MAX_LENGTH = 64;
    public static final int MAX_BYTES = 72;

    private final PasswordPolicyProperties properties;

    public PasswordPolicy(PasswordPolicyProperties properties) {
        this.properties = properties;
    }

    public void validate(String password, String username) {
        if (password == null || password.length() < properties.minLength()) {
            throw weak("密码长度不能少于 " + properties.minLength() + " 位");
        }
        if (password.length() > MAX_LENGTH || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw weak("密码过长（最多 " + MAX_LENGTH + " 个字符且不超过 " + MAX_BYTES + " 字节）");
        }
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean other = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isISOControl(c)) {
                throw weak("密码不能包含控制字符");
            }
            if (c >= 'a' && c <= 'z') {
                lower = true;
            } else if (c >= 'A' && c <= 'Z') {
                upper = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            } else {
                other = true;
            }
        }
        int classes = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
        if (classes < properties.minCharacterClasses()) {
            throw weak("密码需至少包含小写字母、大写字母、数字、符号中的 " + properties.minCharacterClasses() + " 类");
        }
        if (username != null && username.length() >= 3
                && password.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
            throw weak("密码不能包含用户名");
        }
    }

    private static BusinessException weak(String message) {
        return new BusinessException(ErrorCode.PASSWORD_TOO_WEAK, message);
    }
}
