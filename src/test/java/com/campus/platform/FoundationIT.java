package com.campus.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.platform.audit.entity.AuditLog;
import com.campus.platform.audit.entity.AuditResult;
import com.campus.platform.audit.mapper.AuditLogMapper;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.security.Roles;
import com.campus.platform.security.jwt.JwtProperties;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段一基础设施集成测试（真实 MySQL）。
 */
class FoundationIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AuditService auditService;
    @Autowired
    AuditLogMapper auditLogMapper;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JwtProperties jwtProperties;
    @Autowired
    PasswordEncoder encoder;

    // ---------------- 数据库迁移 ----------------

    @Test
    void migrationSeedsBuiltInRolesAndPermissions() {
        List<String> roles = jdbc.queryForList("SELECT code FROM sys_role ORDER BY code", String.class);
        assertThat(roles).containsExactly("ADMIN", "STUDENT", "TEACHER");

        Integer adminPerms = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_role r ON r.id = rp.role_id WHERE r.code='ADMIN'
                """, Integer.class);
        Integer allPerms = jdbc.queryForObject("SELECT COUNT(*) FROM sys_permission", Integer.class);
        assertThat(adminPerms).isEqualTo(allPerms);

        List<String> studentPerms = jdbc.queryForList("""
                SELECT p.code FROM sys_permission p JOIN sys_role_permission rp ON rp.permission_id = p.id
                JOIN sys_role r ON r.id = rp.role_id WHERE r.code = 'STUDENT'
                """, String.class);
        assertThat(studentPerms).contains("selection:select", "leave:submit")
                .doesNotContain("leave:review", "audit:read", "kb:space:create", "course:manage_own");
    }

    // ---------------- 初始管理员引导 ----------------

    @Test
    void bootstrapCreatesSingleAdminWithHashedPassword() {
        SysUser admin = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "it_admin"));
        assertThat(admin).isNotNull();
        assertThat(admin.getPasswordHash()).startsWith("{bcrypt}").doesNotContain("it-admin-password-0001");
        assertThat(encoder.matches("it-admin-password-0001", admin.getPasswordHash())).isTrue();
        assertThat(userMapper.selectRoleCodes(admin.getId())).containsExactly(Roles.ADMIN);
        Long audits = auditLogMapper.selectCount(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getAction, "ADMIN_BOOTSTRAP"));
        assertThat(audits).isEqualTo(1L);
    }

    // ---------------- 公开接口与 Swagger ----------------

    @Test
    void publicEndpointsAreAccessibleWithTraceHeader() throws Exception {
        mvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Shanghai"));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me']").exists());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void safeIncomingRequestIdIsPropagatedButUnsafeIsReplaced() throws Exception {
        mvc.perform(get("/api/v1/system/info").header("X-Request-Id", "gateway-req-12345"))
                .andExpect(header().string("X-Trace-Id", "gateway-req-12345"));
        mvc.perform(get("/api/v1/system/info").header("X-Request-Id", "bad\nvalue injected"))
                .andExpect(header().string("X-Trace-Id", not("bad\nvalue injected")));
    }

    // ---------------- 认证 ----------------

    @Test
    void protectedEndpointWithoutTokenReturns401Json() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId", notNullValue()));
    }

    @Test
    void malformedOrForgedTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        // 用另一把密钥签名的令牌
        String forged = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                        "another-secret-another-secret-another-secret".getBytes()))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder().issuer(jwtProperties.issuer()).subject("1")
                                .expiresAt(Instant.now().plusSeconds(600)).claim("ver", 0).claim("typ", "access")
                                .build()))
                .getTokenValue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        SysUser u = createUser("expired", Roles.STUDENT);
        Instant past = Instant.now().minusSeconds(3600);
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer(jwtProperties.issuer()).subject(u.getId().toString())
                        .issuedAt(past.minusSeconds(600)).expiresAt(past)
                        .claim("ver", 0).claim("typ", "access").build())).getTokenValue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenReturnsCurrentUserWithRolesAndPermissions() throws Exception {
        SysUser student = createUser("stu", Roles.STUDENT);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(student.getId()))
                .andExpect(jsonPath("$.data.username").value(student.getUsername()))
                .andExpect(jsonPath("$.data.roles").value(hasItem("STUDENT")))
                .andExpect(jsonPath("$.data.permissions").value(hasItems("ai:chat", "selection:select")))
                .andExpect(jsonPath("$.data.permissions").value(not(hasItem("audit:read"))))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void tokenIsRevokedWhenTokenVersionChangesOrAccountDisabled() throws Exception {
        SysUser u = createUser("revoke", Roles.TEACHER);
        String token = bearer(u);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token)).andExpect(status().isOk());

        // 模拟修改密码/强制下线
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, u.getId())
                .setSql("token_version = token_version + 1"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token)).andExpect(status().isUnauthorized());

        SysUser u2 = createUser("disabled", Roles.STUDENT);
        String token2 = bearer(u2);
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, u2.getId())
                .set(SysUser::getStatus, UserStatus.DISABLED));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token2)).andExpect(status().isUnauthorized());
    }

    @Test
    void roleChangesTakeEffectWithoutReissuingToken() throws Exception {
        SysUser u = createUser("promote", Roles.STUDENT);
        String token = bearer(u);
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", token)).andExpect(status().isForbidden());
        userMapper.grantRole(u.getId(), Roles.ADMIN);
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", token)).andExpect(status().isOk());
    }

    // ---------------- 授权与审计查询 ----------------

    @Test
    void auditLogsRequireAuditPermission() throws Exception {
        SysUser student = createUser("stu", Roles.STUDENT);
        SysUser teacher = createUser("tea", Roles.TEACHER);
        SysUser admin = createUser("adm", Roles.ADMIN);

        mvc.perform(get("/api/v1/audit-logs").header("Authorization", bearer(student)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", bearer(admin))
                        .param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void pageParametersAreValidated() throws Exception {
        SysUser admin = createUser("adm", Roles.ADMIN);
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", bearer(admin)).param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", bearer(admin))
                        .param("from", "2026-10-02T00:00:00").param("to", "2026-10-01T00:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auditRecordSurvivesBusinessRollbackAndIsMasked() {
        String marker = "ROLLBACK_TEST_" + UUID.randomUUID().toString().substring(0, 8);
        try {
            tx.executeWithoutResult(status -> {
                auditService.record(AuditEvent.builder("test", marker)
                        .target("DOC", 1)
                        .failure("SOME_ERROR")
                        .detail("password", "should-not-be-stored")
                        .detail("note", "Bearer abc.def.ghi")
                        .build());
                throw new IllegalStateException("业务失败，事务回滚");
            });
        } catch (IllegalStateException expected) {
            // 业务事务已回滚
        }
        AuditLog row = auditLogMapper.selectOne(new LambdaQueryWrapper<AuditLog>().eq(AuditLog::getAction, marker));
        assertThat(row).isNotNull();
        assertThat(row.getResult()).isEqualTo(AuditResult.FAILURE);
        assertThat(row.getDetail()).doesNotContain("should-not-be-stored").doesNotContain("abc.def.ghi");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void auditLogsCanBeFilteredAndPaged() throws Exception {
        String action = "FILTER_" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            auditService.record(AuditEvent.builder("test", action).target("X", i).build());
        }
        SysUser admin = createUser("adm", Roles.ADMIN);
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", bearer(admin))
                        .param("action", action).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.pages").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                // 按 id 倒序
                .andExpect(jsonPath("$.data.items[0].targetId").value("2"));
    }

    @Test
    void auditTimestampsAndDatabaseDefaultsUseSameTimeZone() {
        // 会话时区由 connection-init-sql 设为 +08:00
        String tz = jdbc.queryForObject("SELECT @@session.time_zone", String.class);
        assertThat(tz).isEqualTo("+08:00");
    }

    @Test
    void unknownApiReturnsJson404ForAuthenticatedUser() throws Exception {
        SysUser u = createUser("stu", Roles.STUDENT);
        mvc.perform(get("/api/v1/does-not-exist").header("Authorization", bearer(u)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
