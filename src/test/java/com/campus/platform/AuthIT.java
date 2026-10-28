package com.campus.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.platform.audit.entity.AuditLog;
import com.campus.platform.audit.entity.AuditResult;
import com.campus.platform.audit.mapper.AuditLogMapper;
import com.campus.platform.security.Roles;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段二：登录、失败锁定、限流、退出、修改密码（真实 MySQL）。
 */
class AuthIT extends IntegrationTestBase {

    @Autowired
    AuditLogMapper auditLogMapper;

    // ---------------- 登录 ----------------

    @Test
    void loginSuccessReturnsUsableTokenAndRecordsLastLogin() throws Exception {
        SysUser u = createUser("login_ok", Roles.STUDENT);
        MvcResult r = login(u.getUsername(), DEFAULT_PASSWORD, randomIp());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode data = body(r).get("data");
        assertThat(data.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(data.get("expiresIn").asLong()).isPositive();
        assertThat(data.at("/user/roles/0").asText()).isEqualTo("STUDENT");
        assertThat(data.at("/user/passwordHash").isMissingNode()).isTrue();

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + data.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(u.getId()));

        SysUser fresh = userMapper.selectById(u.getId());
        assertThat(fresh.getLastLoginAt()).isNotNull();
        assertThat(fresh.getFailedLoginCount()).isZero();
        assertThat(auditLogMapper.selectCount(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getAction, "LOGIN").eq(AuditLog::getActorUserId, u.getId())
                .eq(AuditLog::getResult, AuditResult.SUCCESS))).isEqualTo(1L);
    }

    @Test
    void unknownUserAndWrongPasswordAreIndistinguishable() throws Exception {
        SysUser u = createUser("enum", Roles.STUDENT);
        String ip = randomIp();
        JsonNode wrongPwd = body(login(u.getUsername(), "Wrong-pass-1", ip));
        String ghost = "ghost_" + suffix();
        MvcResult ghostResult = login(ghost, "Wrong-pass-1", ip);
        JsonNode noUser = body(ghostResult);

        assertThat(ghostResult.getResponse().getStatus()).isEqualTo(401);
        assertThat(noUser.get("code").asText()).isEqualTo("AUTH_BAD_CREDENTIALS").isEqualTo(wrongPwd.get("code").asText());
        assertThat(noUser.get("message").asText()).isEqualTo(wrongPwd.get("message").asText());

        // 两种失败都写审计；不存在的用户没有 actorUserId，但保留尝试的用户名
        AuditLog ghostAudit = auditLogMapper.selectOne(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getAction, "LOGIN").eq(AuditLog::getActorUsername, ghost));
        assertThat(ghostAudit.getResult()).isEqualTo(AuditResult.FAILURE);
        assertThat(ghostAudit.getActorUserId()).isNull();
        assertThat(ghostAudit.getDetail()).doesNotContain("Wrong-pass-1");
    }

    @Test
    void usernameIsTrimmedButPasswordIsNot() throws Exception {
        SysUser u = createUser("trim", Roles.STUDENT);
        assertThat(login("  " + u.getUsername() + " ", DEFAULT_PASSWORD, randomIp()).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(login(u.getUsername(), " " + DEFAULT_PASSWORD, randomIp()).getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void loginRequestIsValidated() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "", "password", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("username"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{bad json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledAndAdminLockedAccountsCannotLoginAndStatusRevealedOnlyWithCorrectPassword() throws Exception {
        SysUser disabled = createUser("dis", Roles.STUDENT);
        setStatus(disabled, UserStatus.DISABLED);
        String ip = randomIp();
        assertThat(body(login(disabled.getUsername(), DEFAULT_PASSWORD, ip)).get("code").asText())
                .isEqualTo("AUTH_ACCOUNT_DISABLED");
        // 密码错误时不暴露账号状态
        assertThat(body(login(disabled.getUsername(), "Wrong-pass-1", ip)).get("code").asText())
                .isEqualTo("AUTH_BAD_CREDENTIALS");

        SysUser locked = createUser("adminlocked", Roles.STUDENT);
        setStatus(locked, UserStatus.LOCKED);
        MvcResult r = login(locked.getUsername(), DEFAULT_PASSWORD, ip);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r).get("code").asText()).isEqualTo("AUTH_ACCOUNT_LOCKED");
        // 停用账号登录不会更新 last_login_at
        assertThat(userMapper.selectById(disabled.getId()).getLastLoginAt()).isNull();
    }

    // ---------------- 连续失败锁定 ----------------

    @Test
    void fiveConsecutiveFailuresLockAccountEvenForCorrectPassword() throws Exception {
        SysUser u = createUser("lock", Roles.STUDENT);
        String ip = randomIp();
        for (int i = 1; i <= 4; i++) {
            assertThat(body(login(u.getUsername(), "Wrong-pass-" + i, ip)).get("code").asText())
                    .isEqualTo("AUTH_BAD_CREDENTIALS");
            assertThat(userMapper.selectById(u.getId()).getFailedLoginCount()).isEqualTo(i);
        }
        // 第 5 次失败触发锁定
        login(u.getUsername(), "Wrong-pass-5", ip);
        SysUser locked = userMapper.selectById(u.getId());
        assertThat(locked.getFailedLoginCount()).isEqualTo(5);
        assertThat(locked.getLockedUntil()).isAfter(LocalDateTime.now().plusMinutes(14));

        // 锁定期间正确密码同样被拒绝，且不再累加计数
        MvcResult r = login(u.getUsername(), DEFAULT_PASSWORD, ip);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r).get("code").asText()).isEqualTo("AUTH_ACCOUNT_LOCKED");
        assertThat(body(r).get("message").asText()).contains("分钟");
        login(u.getUsername(), "Wrong-pass-6", ip);
        assertThat(userMapper.selectById(u.getId()).getFailedLoginCount()).isEqualTo(5);

        assertThat(auditLogMapper.selectCount(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getAction, "ACCOUNT_TEMP_LOCKED").eq(AuditLog::getTargetId, u.getId().toString())))
                .isEqualTo(1L);
    }

    @Test
    void lockExpiresAndCounterRestartsAndSuccessResetsCounter() throws Exception {
        SysUser u = createUser("expire", Roles.STUDENT);
        String ip = randomIp();
        // 模拟锁定已过期
        userMapper.updateLoginFailure(u.getId(), 5, LocalDateTime.now().minusMinutes(1));
        login(u.getUsername(), "Wrong-pass-1", ip);
        SysUser afterFail = userMapper.selectById(u.getId());
        assertThat(afterFail.getFailedLoginCount()).isEqualTo(1);
        assertThat(afterFail.getLockedUntil()).isNull();

        login(u.getUsername(), "Wrong-pass-2", ip);
        assertThat(login(u.getUsername(), DEFAULT_PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);
        assertThat(userMapper.selectById(u.getId()).getFailedLoginCount()).isZero();
    }

    @Test
    void temporaryLockDoesNotRevokeExistingTokens() throws Exception {
        SysUser u = createUser("keep", Roles.STUDENT);
        String token = bearer(u);
        userMapper.updateLoginFailure(u.getId(), 5, LocalDateTime.now().plusMinutes(15));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token)).andExpect(status().isOk());
    }

    @Test
    void concurrentWrongPasswordsAreAllCountedAndLockExactlyOnce() throws Exception {
        SysUser u = createUser("race", Roles.STUDENT);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> login(u.getUsername(), "Wrong-pass-x", randomIp()).getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                assertThat(f.get()).isIn(401, 403);
            }
        } finally {
            pool.shutdown();
        }
        SysUser after = userMapper.selectById(u.getId());
        assertThat(after.getLockedUntil()).isNotNull();
        // 计数在锁定后停止累加：恰好等于阈值，不丢失也不超出
        assertThat(after.getFailedLoginCount()).isEqualTo(5);
        assertThat(auditLogMapper.selectCount(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getAction, "ACCOUNT_TEMP_LOCKED").eq(AuditLog::getTargetId, u.getId().toString())))
                .isEqualTo(1L);
    }

    // ---------------- 限流 ----------------

    @Test
    void sameIpAndUsernameIsRateLimitedWith429AndRetryAfter() throws Exception {
        String ip = randomIp();
        String name = "nobody_" + suffix();
        // 测试配置：同一 IP + 用户名每分钟 10 次
        for (int i = 0; i < 10; i++) {
            assertThat(login(name, "x", ip).getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult limited = login(name.toUpperCase(), "x", ip);
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(limited).get("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isPositive();
        // 其他 IP 不受影响
        assertThat(login(name, "x", randomIp()).getResponse().getStatus()).isEqualTo(401);
    }

    // ---------------- 退出 ----------------

    @Test
    void logoutRevokesAllTokensOfUser() throws Exception {
        SysUser u = createUser("logout", Roles.TEACHER);
        String t1 = bearer(u);
        String t2 = bearer(u);
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", t1)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", t1)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", t2)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
    }

    // ---------------- 修改密码 ----------------

    @Test
    void changePasswordRevokesOldTokensAndReturnsNewOne() throws Exception {
        SysUser u = createUser("chpwd", Roles.STUDENT);
        String old = bearer(u);
        MvcResult r = mvc.perform(put("/api/v1/auth/password").header("Authorization", old)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("oldPassword", DEFAULT_PASSWORD, "newPassword", "N3w-Secret-pass"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        String fresh = "Bearer " + body(r).at("/data/accessToken").asText();

        mvc.perform(get("/api/v1/auth/me").header("Authorization", old)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", fresh)).andExpect(status().isOk());

        String ip = randomIp();
        assertThat(login(u.getUsername(), DEFAULT_PASSWORD, ip).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(u.getUsername(), "N3w-Secret-pass", ip).getResponse().getStatus()).isEqualTo(200);

        SysUser after = userMapper.selectById(u.getId());
        assertThat(after.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(after.getPasswordChangedAt()).isNotNull();
    }

    @Test
    void changePasswordRejectsWrongOldWeakOrSamePassword() throws Exception {
        SysUser u = createUser("chpwd_bad", Roles.STUDENT);
        String token = bearer(u);
        mvc.perform(put("/api/v1/auth/password").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("oldPassword", "Not-the-old-1", "newPassword", "N3w-Secret-pass"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_MISMATCH"));
        mvc.perform(put("/api/v1/auth/password").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("oldPassword", DEFAULT_PASSWORD, "newPassword", "weakweak"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_WEAK"));
        mvc.perform(put("/api/v1/auth/password").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("oldPassword", DEFAULT_PASSWORD, "newPassword", DEFAULT_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_WEAK"));
        // 失败不吊销令牌
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token)).andExpect(status().isOk());
    }

    @Test
    void changePasswordIsRateLimitedPerUser() throws Exception {
        SysUser u = createUser("chpwd_rl", Roles.STUDENT);
        String token = bearer(u);
        int last = 0;
        for (int i = 0; i < 11; i++) {
            last = mvc.perform(put("/api/v1/auth/password").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("oldPassword", "Guess-" + i + "-x", "newPassword", "N3w-Secret-pass"))))
                    .andReturn().getResponse().getStatus();
        }
        assertThat(last).isEqualTo(429);
    }

    private void setStatus(SysUser u, UserStatus status) {
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>().eq(SysUser::getId, u.getId())
                .set(SysUser::getStatus, status));
    }
}
