package com.campus.platform.audit.service;

import com.campus.platform.audit.entity.AuditResult;
import com.campus.platform.audit.entity.AuditSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条待写入的审计事件。使用 {@link #builder(String, String)} 构造。
 * <p>
 * detail 中的值会被自动脱敏和截断，但调用方仍不应主动放入密码、密钥等秘密信息。
 */
public final class AuditEvent {

    final String module;
    final String action;
    final AuditSource source;
    final AuditResult result;
    final String targetType;
    final String targetId;
    final String errorCode;
    final Map<String, Object> detail;
    /** 未登录场景（如登录失败）显式指定操作人；为空时取当前登录用户 */
    final Long actorUserId;
    final String actorUsername;

    private AuditEvent(Builder b) {
        this.module = b.module;
        this.action = b.action;
        this.source = b.source;
        this.result = b.result;
        this.targetType = b.targetType;
        this.targetId = b.targetId;
        this.errorCode = b.errorCode;
        this.detail = b.detail;
        this.actorUserId = b.actorUserId;
        this.actorUsername = b.actorUsername;
    }

    /**
     * @param module 模块，如 knowledge
     * @param action 动作，如 KB_DOCUMENT_DELETE
     */
    public static Builder builder(String module, String action) {
        return new Builder(module, action);
    }

    public static final class Builder {
        private final String module;
        private final String action;
        private AuditSource source = AuditSource.API;
        private AuditResult result = AuditResult.SUCCESS;
        private String targetType;
        private String targetId;
        private String errorCode;
        private final Map<String, Object> detail = new LinkedHashMap<>();
        private Long actorUserId;
        private String actorUsername;

        private Builder(String module, String action) {
            this.module = module;
            this.action = action;
        }

        public Builder source(AuditSource source) {
            this.source = source;
            return this;
        }

        public Builder result(AuditResult result) {
            this.result = result;
            return this;
        }

        public Builder target(String type, Object id) {
            this.targetType = type;
            this.targetId = id == null ? null : String.valueOf(id);
            return this;
        }

        public Builder failure(String errorCode) {
            this.result = AuditResult.FAILURE;
            this.errorCode = errorCode;
            return this;
        }

        public Builder denied() {
            this.result = AuditResult.DENIED;
            this.errorCode = "FORBIDDEN";
            return this;
        }

        public Builder detail(String key, Object value) {
            this.detail.put(key, value);
            return this;
        }

        public Builder actor(Long userId, String username) {
            this.actorUserId = userId;
            this.actorUsername = username;
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(this);
        }
    }
}
