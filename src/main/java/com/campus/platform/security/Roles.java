package com.campus.platform.security;

/**
 * 内置角色编码，与 sys_role.code 对应。
 */
public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String TEACHER = "TEACHER";
    public static final String STUDENT = "STUDENT";
    /** 学生会：学生能力 + 只读查询请假与考勤 */
    public static final String STUDENT_UNION = "STUDENT_UNION";

    /** Spring Security 角色权限前缀 */
    public static final String ROLE_PREFIX = "ROLE_";

    private Roles() {
    }
}
