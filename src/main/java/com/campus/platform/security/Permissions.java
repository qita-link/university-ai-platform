package com.campus.platform.security;

import com.campus.platform.common.exception.BusinessException;

/**
 * 权限点编码常量（与 sys_permission.code 对应），以及 Service 层的功能权限断言。
 * <p>
 * Controller 上的 {@code @PreAuthorize} 只保护 HTTP 入口；Service 方法同样会被 AI 工具等非 HTTP 入口调用，
 * 因此敏感的 Service 方法内部也要调用 {@link #require(AuthenticatedUser, String)}。
 */
public final class Permissions {

    public static final String USER_READ = "user:read";
    public static final String USER_MANAGE = "user:manage";
    public static final String ROLE_MANAGE = "role:manage";
    public static final String ORG_MANAGE = "org:manage";
    public static final String AUDIT_READ = "audit:read";

    public static final String KB_SPACE_READ = "kb:space:read";
    public static final String KB_SPACE_CREATE = "kb:space:create";
    public static final String KB_SPACE_MANAGE_ALL = "kb:space:manage_all";
    public static final String KB_DOCUMENT_WRITE = "kb:document:write";
    public static final String KB_CATEGORY_MANAGE = "kb:category:manage";

    public static final String AI_CHAT = "ai:chat";
    public static final String AI_TOOL_USE = "ai:tool:use";

    public static final String FORUM_POST_WRITE = "forum:post:write";
    public static final String FORUM_COMMENT_WRITE = "forum:comment:write";
    public static final String FORUM_MODERATE = "forum:moderate";
    public static final String FORUM_CATEGORY_MANAGE = "forum:category:manage";

    public static final String COURSE_READ = "course:read";
    public static final String COURSE_MANAGE_OWN = "course:manage_own";
    public static final String COURSE_MANAGE_ALL = "course:manage_all";
    public static final String SELECTION_SELECT = "selection:select";
    public static final String SELECTION_MANAGE_ALL = "selection:manage_all";
    public static final String LEAVE_SUBMIT = "leave:submit";
    public static final String LEAVE_REVIEW = "leave:review";
    public static final String LEAVE_MANAGE_ALL = "leave:manage_all";

    /** 查看排课基础数据、任务、版本与全部课表 */
    public static final String SCHEDULE_READ = "schedule:read";
    /** 维护排课基础数据、执行排课与调整课表 */
    public static final String SCHEDULE_MANAGE = "schedule:manage";
    /** 调整课表：移动课时、锁定/解锁、局部重排 */
    public static final String SCHEDULE_ADJUST = "schedule:adjust";
    /** 发布/撤销发布课表 */
    public static final String SCHEDULE_PUBLISH = "schedule:publish";
    /** 教师维护本人的排课时间偏好 */
    public static final String SCHEDULE_PREFERENCE_SELF = "schedule:preference:self";
    /** 查看本人已发布课表（含学生） */
    public static final String TIMETABLE_READ = "timetable:read";

    private Permissions() {
    }

    /** 当前用户不具备指定权限点时抛出 FORBIDDEN。 */
    public static void require(AuthenticatedUser user, String permission) {
        if (user == null || !user.hasPermission(permission)) {
            throw BusinessException.forbidden();
        }
    }

    /** 具备任意一个权限点即可通过，否则抛出 FORBIDDEN。 */
    public static void requireAny(AuthenticatedUser user, String... permissions) {
        if (user == null) {
            throw BusinessException.forbidden();
        }
        for (String permission : permissions) {
            if (user.hasPermission(permission)) {
                return;
            }
        }
        throw BusinessException.forbidden();
    }
}
