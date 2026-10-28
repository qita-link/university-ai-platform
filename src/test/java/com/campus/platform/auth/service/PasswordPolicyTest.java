package com.campus.platform.auth.service;

import com.campus.platform.auth.config.PasswordPolicyProperties;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(new PasswordPolicyProperties(8, 3));

    @ParameterizedTest
    @ValueSource(strings = {"Abcdef12", "abc123!@#", "ABC-def-99", "密码Abc12345"})
    void acceptsStrongPasswords(String pwd) {
        assertThatCode(() -> policy.validate(pwd, "20260001")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ab1!", "abcdefgh", "abcd1234", "ABCDEFG1"})
    void rejectsShortOrLowVarietyPasswords(String pwd) {
        assertThatThrownBy(() -> policy.validate(pwd, "u"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_TOO_WEAK);
    }

    @Test
    void rejectsPasswordContainingUsernameIgnoringCase() {
        assertThatThrownBy(() -> policy.validate("Zhangsan#2026", "zhangsan"))
                .hasMessageContaining("用户名");
    }

    @Test
    void rejectsPasswordsBcryptCannotHashSafely() {
        // 72 字节以上：bcrypt 会忽略或拒绝超出部分
        String longAscii = "Aa1!" + "x".repeat(70);
        assertThatThrownBy(() -> policy.validate(longAscii, "u")).hasMessageContaining("过长");
        // 25 个汉字 = 75 字节，虽然字符数没超
        String longUtf8 = "Aa1" + "汉".repeat(25);
        assertThat(longUtf8.length()).isLessThan(PasswordPolicy.MAX_LENGTH);
        assertThatThrownBy(() -> policy.validate(longUtf8, "u")).hasMessageContaining("过长");
    }

    @Test
    void rejectsControlCharacters() {
        assertThatThrownBy(() -> policy.validate("Abc12345\n", "u")).hasMessageContaining("控制字符");
    }

    @Test
    void errorMessageNeverEchoesPassword() {
        assertThatThrownBy(() -> policy.validate("weakpass", "u"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("weakpass"));
    }
}
