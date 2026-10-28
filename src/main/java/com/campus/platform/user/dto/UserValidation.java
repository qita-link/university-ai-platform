package com.campus.platform.user.dto;

/** 用户相关 DTO 共用的校验规则 */
public final class UserValidation {

    public static final String USERNAME_REGEX = "^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$";
    public static final String USERNAME_MESSAGE = "用户名为 3~64 位字母、数字、下划线、点或短横线，且以字母或数字开头";
    public static final String PHONE_REGEX = "^\\+?[0-9-]{5,20}$";
    public static final String PHONE_MESSAGE = "手机号格式不正确";
    public static final String ROLE_CODE_REGEX = "^[A-Z][A-Z0-9_]{1,31}$";

    private UserValidation() {
    }
}
