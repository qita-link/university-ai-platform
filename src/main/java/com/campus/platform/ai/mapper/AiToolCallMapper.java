package com.campus.platform.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.platform.ai.entity.AiToolCall;
import com.campus.platform.ai.entity.AiToolCallStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 工具调用记录 Mapper。
 * <p>
 * 所有状态流转都用“带前置状态的条件更新”，由数据库保证并发下只有一个请求成功：
 * 认领（PENDING_CONFIRM → EXECUTING）、终结（EXECUTING → SUCCEEDED/FAILED）、
 * 拒绝（PENDING_CONFIRM → REJECTED）、过期（PENDING_CONFIRM → EXPIRED）。
 */
@Mapper
public interface AiToolCallMapper extends BaseMapper<AiToolCall> {

    /** 认领待确认的调用；确认已过期时返回 0，由调用方标记 EXPIRED。 */
    @Update("""
            UPDATE ai_tool_call
               SET status = 'EXECUTING', confirmed_at = #{now}, updated_at = #{now}, version = version + 1
             WHERE id = #{id} AND user_id = #{userId} AND status = 'PENDING_CONFIRM'
               AND confirm_expires_at IS NOT NULL AND confirm_expires_at > #{now}
            """)
    int claim(@Param("id") long id, @Param("userId") long userId, @Param("now") LocalDateTime now);

    /** 把仍未确认的调用标记为过期。 */
    @Update("""
            UPDATE ai_tool_call
               SET status = 'EXPIRED', updated_at = #{now}, version = version + 1
             WHERE id = #{id} AND user_id = #{userId} AND status = 'PENDING_CONFIRM'
            """)
    int markExpired(@Param("id") long id, @Param("userId") long userId, @Param("now") LocalDateTime now);

    /** 执行结束：EXECUTING → SUCCEEDED/FAILED。 */
    @Update("""
            UPDATE ai_tool_call
               SET status = #{status}, result_summary = #{summary}, error_code = #{errorCode},
                   error_message = #{errorMessage}, executed_at = #{now}, latency_ms = #{latencyMs},
                   updated_at = #{now}, version = version + 1
             WHERE id = #{id} AND status = 'EXECUTING'
            """)
    int finish(@Param("id") long id, @Param("status") AiToolCallStatus status,
               @Param("summary") String summary, @Param("errorCode") String errorCode,
               @Param("errorMessage") String errorMessage, @Param("latencyMs") Integer latencyMs,
               @Param("now") LocalDateTime now);

    /** 拒绝待确认的调用。 */
    @Update("""
            UPDATE ai_tool_call
               SET status = 'REJECTED', error_message = #{reason}, executed_at = #{now},
                   updated_at = #{now}, version = version + 1
             WHERE id = #{id} AND user_id = #{userId} AND status = 'PENDING_CONFIRM'
            """)
    int reject(@Param("id") long id, @Param("userId") long userId,
               @Param("reason") String reason, @Param("now") LocalDateTime now);
}
