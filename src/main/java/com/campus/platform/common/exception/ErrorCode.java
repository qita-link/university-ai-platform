package com.campus.platform.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 全局错误码。
 * <p>
 * 命名规则：通用错误使用无前缀名称；模块错误使用 {@code 模块_原因}，如 {@code SELECTION_COURSE_FULL}。
 * 新增模块错误码时在此追加，保持错误码全局唯一、语义稳定（前端可据此做分支处理）。
 */
public enum ErrorCode {

    // ---- 通用 ----
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求参数有误"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "参数校验失败"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未登录或登录已失效"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有权限执行该操作"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "不支持的请求方法"),
    CONFLICT(HttpStatus.CONFLICT, "数据冲突，请刷新后重试"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "上传内容超过大小限制"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的内容类型"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "不满足业务规则"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请稍后再试"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "服务暂不可用，请稍后再试"),

    // ---- 认证 ----
    AUTH_BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    AUTH_ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "账号已停用"),
    AUTH_ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "账号已锁定，请稍后再试"),
    /** 修改本人密码时原密码不正确（不用 401，避免前端误判为登录失效） */
    AUTH_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "原密码错误"),
    PASSWORD_TOO_WEAK(HttpStatus.BAD_REQUEST, "密码不符合强度要求"),

    // ---- 用户管理 ----
    USER_SELF_OPERATION_FORBIDDEN(HttpStatus.UNPROCESSABLE_ENTITY, "不能对自己的账号执行该操作"),
    USER_LAST_ADMIN(HttpStatus.UNPROCESSABLE_ENTITY, "不能停用或移除最后一个可用的管理员"),

    // ---- 组织单元 ----
    ORG_INVALID_PARENT(HttpStatus.UNPROCESSABLE_ENTITY, "上级组织不合法"),
    ORG_HAS_ACTIVE_CHILDREN(HttpStatus.UNPROCESSABLE_ENTITY, "该组织下仍有启用的下级组织"),
    ORG_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "组织不存在或已停用"),

    // ---- 知识库 ----
    KB_CATEGORY_IN_USE(HttpStatus.CONFLICT, "分类下仍有知识库，不能删除"),
    KB_CATEGORY_DEPTH_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "分类层级过深"),
    KB_SPACE_INVALID_VISIBILITY(HttpStatus.UNPROCESSABLE_ENTITY, "知识库可见范围不合法"),
    KB_SPACE_ORG_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "组织可见的知识库必须指定组织范围"),
    KB_DOCUMENT_INVALID_FILE(HttpStatus.BAD_REQUEST, "文件格式或内容不合法"),
    KB_DOCUMENT_EMPTY(HttpStatus.BAD_REQUEST, "文档内容不能为空"),

    // ---- 论坛 ----
    FORUM_CATEGORY_IN_USE(HttpStatus.CONFLICT, "分类下仍有帖子，不能删除"),
    FORUM_CATEGORY_DISABLED(HttpStatus.UNPROCESSABLE_ENTITY, "分类不存在或已停用"),
    FORUM_POST_STATUS_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "帖子状态变更不合法"),
    FORUM_COMMENT_PARENT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "被回复的评论不属于该帖子或已删除"),

    // ---- 课程与排课 ----
    SEMESTER_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "学期已结束，不能再修改课程"),
    COURSE_STATUS_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "课程状态变更不合法"),
    COURSE_CAPACITY_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "课程容量不能小于已选人数"),
    COURSE_SCHEDULE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "排课信息不合法"),
    COURSE_SCHEDULE_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY, "排课时间与已有课程冲突"),
    COURSE_TEACHER_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "课程至少需要一名主讲教师"),
    COURSE_TEACHER_ROLE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "授课教师角色变更不合法"),

    // ---- 选课 ----
    SELECTION_WINDOW_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "当前不在选课时间窗口内"),
    SELECTION_COURSE_NOT_OPEN(HttpStatus.UNPROCESSABLE_ENTITY, "课程未开放选课"),
    SELECTION_COURSE_FULL(HttpStatus.UNPROCESSABLE_ENTITY, "课程容量已满"),
    SELECTION_DUPLICATED(HttpStatus.CONFLICT, "已经选择过该课程"),
    SELECTION_TIME_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY, "与已选课程时间冲突"),
    SELECTION_NOT_ENROLLED(HttpStatus.UNPROCESSABLE_ENTITY, "未选择该课程"),

    // ---- 请假 ----
    LEAVE_STATUS_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "请假状态变更不合法"),
    LEAVE_TIME_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "请假时间不合法"),

    // ---- AI 工具调用 ----
    AI_TOOL_ARGUMENT_INVALID(HttpStatus.BAD_REQUEST, "工具参数不合法"),
    AI_TOOL_CONFIRM_EXPIRED(HttpStatus.CONFLICT, "确认已过期，请重新发起"),
    AI_TOOL_STATUS_INVALID(HttpStatus.CONFLICT, "工具调用当前状态不允许该操作");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
