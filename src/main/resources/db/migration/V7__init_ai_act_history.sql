-- =====================================================================
-- V7: 办事历史
-- 一次「说一句，把事办掉」一条记录，用于界面的“最近办过的事”与事后追溯。
--
-- 为什么不复用 ai_conversation：
--   问答会话按对话轮次组织，模型回答受知识库引用约束；
--   办事是“一句话 -> 一串工具调用”的独立过程，一次一个意图，且写操作会挂待确认单。
--   两者混在一张表里会让会话列表出现大量只有一问一答的碎片。
--
-- steps_json 保存该次办事的完整执行轨迹（JSON 数组），界面据此回放；
-- 与 ai_tool_call 的对应关系通过 steps_json 中的 callId 建立，不重复存业务结果。
-- =====================================================================

CREATE TABLE ai_act_history (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    user_id     BIGINT        NOT NULL COMMENT '发起办事的用户',
    message     VARCHAR(1000) NOT NULL COMMENT '用户原话',
    reply       TEXT          NULL COMMENT '给用户的答复',
    outcome     VARCHAR(16)   NOT NULL COMMENT 'DONE/WAITING_CONFIRM/MAX_ROUNDS/RAW_TEXT',
    step_count  INT           NOT NULL DEFAULT 0 COMMENT '本次调用了几个工具',
    steps_json  MEDIUMTEXT    NULL COMMENT '执行轨迹（AgentStep 数组）',
    pending_id  BIGINT        NULL COMMENT '写操作生成的待确认记录 ID',
    model       VARCHAR(64)   NULL COMMENT '实际使用的模型',
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_act_user_created (user_id, created_at),
    KEY idx_ai_act_outcome (outcome, created_at),
    CONSTRAINT fk_ai_act_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 自然语言办事记录';

-- 存量数据：确认期已过的待确认记录直接置为 EXPIRED。
-- 之前只有“点确认”时才会发现过期，导致界面上长期挂着一条点不动的待确认。
UPDATE ai_tool_call
   SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP(3), version = version + 1
 WHERE status = 'PENDING_CONFIRM'
   AND confirm_expires_at IS NOT NULL
   AND confirm_expires_at <= CURRENT_TIMESTAMP(3);
