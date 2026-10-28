package com.campus.platform.audit.entity;

/** 操作来源 */
public enum AuditSource {
    /** 普通 REST API 调用 */
    API,
    /** AI 工具调用（同一业务服务，经 AI 发起） */
    AI_TOOL,
    /** 系统任务（启动引导、后台处理） */
    SYSTEM
}
