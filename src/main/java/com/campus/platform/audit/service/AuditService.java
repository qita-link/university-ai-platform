package com.campus.platform.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.audit.dto.AuditLogQuery;
import com.campus.platform.audit.dto.AuditLogView;
import com.campus.platform.audit.entity.AuditLog;
import com.campus.platform.audit.mapper.AuditLogMapper;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.util.SensitiveDataMasker;
import com.campus.platform.common.web.ClientInfo;
import com.campus.platform.common.web.TraceContext;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 审计日志服务。
 * <ul>
 *   <li>写入使用独立事务（REQUIRES_NEW）：业务事务回滚时，失败/拒绝记录仍然保留；</li>
 *   <li>审计写入失败只记录错误日志，不影响业务结果（审计是旁路能力）；</li>
 *   <li>detail 自动脱敏并限制长度。</li>
 * </ul>
 */
@Slf4j
@Service
public class AuditService {

    static final int DETAIL_MAX_LENGTH = 2000;
    static final int DETAIL_VALUE_MAX_LENGTH = 200;

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTx;
    private final Clock clock;

    public AuditService(AuditLogMapper auditLogMapper, ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 记录审计事件；任何异常都被吞掉并写错误日志。 */
    public void record(AuditEvent event) {
        try {
            AuditLog row = toEntity(event);
            requiresNewTx.executeWithoutResult(status -> auditLogMapper.insert(row));
        } catch (RuntimeException ex) {
            log.error("审计日志写入失败: module={}, action={}", event.module, event.action, ex);
        }
    }

    /** 分页查询审计日志（调用方需具备 audit:read 权限，由 Controller 层校验）。 */
    public PageResult<AuditLogView> query(AuditLogQuery q) {
        if (q.getFrom() != null && q.getTo() != null && q.getFrom().isAfter(q.getTo())) {
            throw BusinessException.badRequest("开始时间不能晚于结束时间");
        }
        LambdaQueryWrapper<AuditLog> w = new LambdaQueryWrapper<AuditLog>()
                .eq(q.getActorUserId() != null, AuditLog::getActorUserId, q.getActorUserId())
                .eq(q.getModule() != null, AuditLog::getModule, q.getModule())
                .eq(q.getAction() != null, AuditLog::getAction, q.getAction())
                .eq(q.getSource() != null, AuditLog::getSource, q.getSource())
                .eq(q.getResult() != null, AuditLog::getResult, q.getResult())
                .eq(q.getTargetType() != null, AuditLog::getTargetType, q.getTargetType())
                .eq(q.getTargetId() != null, AuditLog::getTargetId, q.getTargetId())
                .ge(q.getFrom() != null, AuditLog::getCreatedAt, q.getFrom())
                .lt(q.getTo() != null, AuditLog::getCreatedAt, q.getTo())
                .orderByDesc(AuditLog::getId);
        return PageResult.of(auditLogMapper.selectPage(q.toPage(), w), AuditLogView::from);
    }

    private AuditLog toEntity(AuditEvent e) {
        AuditLog row = new AuditLog();
        row.setTraceId(TraceContext.currentTraceId());
        Optional<AuthenticatedUser> current = SecurityUtils.currentUser();
        row.setActorUserId(e.actorUserId != null ? e.actorUserId : current.map(AuthenticatedUser::userId).orElse(null));
        row.setActorUsername(e.actorUsername != null ? e.actorUsername
                : current.map(AuthenticatedUser::username).orElse(null));
        row.setSource(e.source);
        row.setModule(e.module);
        row.setAction(e.action);
        row.setTargetType(e.targetType);
        row.setTargetId(e.targetId);
        row.setResult(e.result);
        row.setErrorCode(e.errorCode);
        row.setDetail(serializeDetail(e.detail));
        ClientInfo client = ClientInfo.current();
        row.setClientIp(client.ip());
        row.setUserAgent(client.userAgent());
        row.setCreatedAt(LocalDateTime.now(clock));
        return row;
    }

    private String serializeDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(SensitiveDataMasker.maskMap(detail, DETAIL_VALUE_MAX_LENGTH));
            return SensitiveDataMasker.truncate(json, DETAIL_MAX_LENGTH - 16);
        } catch (JsonProcessingException ex) {
            return "{\"_error\":\"detail serialization failed\"}";
        }
    }
}
