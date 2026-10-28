-- =====================================================================
-- V6: AI 工具调用记录
-- 约定与 V1~V5 一致：InnoDB / utf8mb4 / DATETIME(3) / BIGINT 自增主键。
-- 一次工具调用一条记录；只读工具直接得到结果，写工具先落 PENDING_CONFIRM，
-- 用户确认后才执行（EXECUTING → SUCCEEDED/FAILED），拒绝为 REJECTED，
-- 超过 confirm_expires_at 未确认为 EXPIRED。
-- arguments_json 保存调用参数快照（结构随工具变化，故使用 JSON 列）；
-- result_summary 只保存可读摘要，不保存完整业务对象，避免表膨胀。
-- =====================================================================

CREATE TABLE ai_tool_call (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    conversation_id    BIGINT        NULL COMMENT '关联的 AI 会话，可空',
    user_id            BIGINT        NOT NULL COMMENT '发起调用的用户',
    tool_name          VARCHAR(64)   NOT NULL,
    side_effect        BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '是否写操作（需确认）',
    arguments_json     JSON          NOT NULL COMMENT '调用参数快照',
    argument_summary   VARCHAR(500)  NULL COMMENT '参数可读摘要，供列表与审计展示',
    preview            VARCHAR(1000) NULL COMMENT '待确认时展示的目标与影响',
    status             VARCHAR(16)   NOT NULL DEFAULT 'PENDING_CONFIRM'
        COMMENT 'PENDING_CONFIRM/EXECUTING/SUCCEEDED/FAILED/REJECTED/EXPIRED',
    result_summary     VARCHAR(2000) NULL COMMENT '执行结果摘要',
    error_code         VARCHAR(64)   NULL,
    error_message      VARCHAR(500)  NULL,
    confirm_expires_at DATETIME(3)   NULL COMMENT '确认截止时间；只读工具为空',
    confirmed_at       DATETIME(3)   NULL,
    executed_at        DATETIME(3)   NULL,
    latency_ms         INT           NULL,
    version            INT           NOT NULL DEFAULT 0 COMMENT '状态变更次数',
    created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_tool_call_user (user_id, created_at),
    KEY idx_ai_tool_call_status (status, confirm_expires_at),
    KEY idx_ai_tool_call_conversation (conversation_id, id),
    KEY idx_ai_tool_call_tool (tool_name, created_at),
    CONSTRAINT fk_ai_tool_call_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_tool_call_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 工具调用记录';
