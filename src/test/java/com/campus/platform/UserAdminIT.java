package com.campus.platform;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Roles;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.service.UserAdminService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段二：管理员用户管理与保护规则（真实 MySQL）。
 */
class UserAdminIT extends IntegrationTestBase {

    @Autowired
    UserAdminService userAdminService;

    // ---------------- 创建、查询、修改 ----------------

    @Test
    void adminCreatesUserWhoCanThenLogIn() throws Exception {
        SysUser admin = createUser("adm", Roles.ADMIN);
        SysOrgUnit college = createOrg(null, OrgType.COLLEGE);
        String username = "s" + suffix();
        Map<String, Object> req = new HashMap<>();
        req.put("username", username);
        req.put("displayName", "张三");
        req.put("password", "Init-pass-2026");
        req.put("email", username + "@campus.test");
        req.put("orgUnitId", college.getId());
        req.put("roleCodes", List.of("STUDENT"));
        MvcResult r = mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.roles[0]").value("STUDENT"))
                .andExpect(jsonPath("$.data.orgUnitName").value(college.getName()))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.tokenVersion").doesNotExist())
                .andReturn();
        long id = body(r).at("/data/id").asLong();
        assertThat(userMapper.selectById(id).getPasswordHash()).startsWith("{bcrypt}");
        assertThat(login(username, "Init-pass-2026", randomIp()).getResponse().getStatus()).isEqualTo(200);

        // 重名（排序规则不区分大小写）、弱密码、未知角色、停用组织
        req.put("username", username.toUpperCase());
        req.put("email", null);
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isConflict());
        req.put("username", "s" + suffix());
        req.put("password", "weakweak");
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_TOO_WEAK"));
        req.put("password", "Init-pass-2026");
        req.put("roleCodes", List.of("SUPERUSER"));
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isBadRequest());
        req.put("roleCodes", List.of("STUDENT"));
        req.put("orgUnitId", 99_999_999L);
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ORG_UNAVAILABLE"));
    }

    @Test
    void listFiltersByKeywordRoleAndOrgSubtree() throws Exception {
        SysUser admin = createUser("adm", Roles.ADMIN);
        SysOrgUnit college = createOrg(null, OrgType.COLLEGE);
        SysOrgUnit clazz = createOrg(college.getId(), OrgType.CLASS);
        String tag = suffix();
        SysUser s1 = createUserInOrg("lst" + tag, clazz.getId(), Roles.STUDENT);
        SysUser t1 = createUserInOrg("lst" + tag, college.getId(), Roles.TEACHER);

        mvc.perform(get("/api/v1/users").header("Authorization", bearer(admin)).param("keyword", "lst" + tag))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(admin))
                        .param("keyword", "lst" + tag).param("roleCode", "TEACHER"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(t1.getId()));
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(admin))
                        .param("orgUnitId", college.getId().toString()))
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(admin))
                        .param("orgUnitId", college.getId().toString()).param("includeSubOrgs", "true"))
                .andExpect(jsonPath("$.data.total").value(2));
        // LIKE 通配符被转义：% 不会匹配全部用户
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(admin)).param("keyword", "%"))
                .andExpect(jsonPath("$.data.total").value(0));
        // 非法角色编码（防注入）在校验层被拒绝
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(admin)).param("roleCode", "X' OR '1'='1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users/" + s1.getId()).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.roles[0]").value("STUDENT"));
        mvc.perform(get("/api/v1/users/99999999").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateUsesOptimisticLock() throws Exception {
        SysUser admin = createUser("adm", Roles.ADMIN);
        SysUser u = createUser("upd", Roles.STUDENT);
        int v = userMapper.selectById(u.getId()).getVersion();
        Map<String, Object> req = new HashMap<>();
        req.put("displayName", "新名字");
        req.put("version", v);
        mvc.perform(put("/api/v1/users/" + u.getId()).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("新名字"))
                .andExpect(jsonPath("$.data.version").value(v + 1));
        // 使用过期的 version 再次提交
        req.put("displayName", "覆盖");
        mvc.perform(put("/api/v1/users/" + u.getId()).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(userMapper.selectById(u.getId()).getDisplayName()).isEqualTo("新名字");
    }

    @Test
    void concurrentUpdatesWithSameVersionOnlyOneWins() throws Exception {
        SysUser admin = createUser("adm", Roles.ADMIN);
        SysUser u = createUser("cc", Roles.STUDENT);
        int v = userMapper.selectById(u.getId()).getVersion();
        String token = bearer(admin);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String name = "并发" + i;
            tasks.add(() -> mvc.perform(put("/api/v1/users/" + u.getId()).header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("displayName", name, "version", v))))
                    .andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(userMapper.selectById(u.getId()).getVersion()).isEqualTo(v + 1);
    }

    @Test
    void loginCounterUpdatesDoNotBreakOptimisticLockOfAdminEdits() throws Exception {
        SysUser admin = createUser("adm", Roles.ADMIN);
        SysUser u = createUser("ctr", Roles.STUDENT);
        int v = userMapper.selectById(u.getId()).getVersion();
        // 管理员编辑期间用户输错一次密码、又成功登录一次
        login(u.getUsername(), "Wrong-pass-1", randomIp());
        login(u.getUsername(), DEFAULT_PASSWORD, randomIp());
        mvc.perform(put("/api/v1/users/" + u.getId()).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("displayName", "仍可保存", "version", v))))
                .andExpect(status().isOk());
    }

    // ---------------- 状态、解锁、重置密码 ----------------

    @Test
    void disablingRevokesTokensAndReEnablingDoesNotRestoreThem() throws Exception {
        SysUser admin = createUser("adm", Roles.ADMIN);
        SysUser u = createUser("dis", Roles.TEACHER);
        String old = bearer(u);
        patchStatus(admin, u, "DISABLED").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISABLED"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", old)).andExpect(status().isUnauthorized());
        patchStatus(admin, u, "ACTIVE").andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", old)).andExpect(status().isUnauthorized());
        assertThat(login(u.getUsername(), DEFAULT_PASSWORD, randomIp()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unlockAndEnableClearTemporaryLock() throws Exception {
        SysUser admin = createUser("adm", Roles.ADMIN);
        SysUser u = createUser("unl", Roles.STUDENT);
        userMapper.updateLoginFailure(u.getId(), 5, LocalDateTime.now().plusMinutes(15));
        mvc.perform(get("/api/v1/users/" + u.getId()).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.lockedUntil").isNotEmpty())
                .andExpect(jsonPath("$.data.failedLoginCount").value(5));
        mvc.perform(post("/api/v1/users/" + u.getId() + "/unlock").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lockedUntil").doesNotExist())
                .andExpect(jsonPath("$.data.failedLoginCount").value(0));
        assertThat(login(u.getUsername(), DEFAULT_PASSWORD, randomIp()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void resetPasswordRevokesTokensAndRequiresStrongPassword() throws Exception {
        SysUser admin = createUser("adm", Roles.ADMIN);
        SysUser u = createUser("rst", Roles.STUDENT);
        String old = bearer(u);
        mvc.perform(post("/api/v1/users/" + u.getId() + "/password-reset").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("newPassword", "short"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/users/" + u.getId() + "/password-reset").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("newPassword", "Reset-pass-2026"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", old)).andExpect(status().isUnauthorized());
        assertThat(login(u.getUsername(), "Reset-pass-2026", randomIp()).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------------- 保护规则：自己 ----------------

    @Test
    void adminCannotDisableLockDemoteOrResetSelf() throws Exception {
        SysUser admin = createUser("self", Roles.ADMIN);
        patchStatus(admin, admin, "DISABLED")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("USER_SELF_OPERATION_FORBIDDEN"));
        patchStatus(admin, admin, "LOCKED").andExpect(jsonPath("$.code").value("USER_SELF_OPERATION_FORBIDDEN"));
        mvc.perform(put("/api/v1/users/" + admin.getId() + "/roles").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("roleCodes", List.of("TEACHER")))))
                .andExpect(jsonPath("$.code").value("USER_SELF_OPERATION_FORBIDDEN"));
        mvc.perform(post("/api/v1/users/" + admin.getId() + "/password-reset").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("newPassword", "Reset-pass-2026"))))
                .andExpect(jsonPath("$.code").value("USER_SELF_OPERATION_FORBIDDEN"));
        assertThat(userMapper.selectById(admin.getId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(userMapper.selectRoleCodes(admin.getId())).containsExactly(Roles.ADMIN);
    }

    // ---------------- 保护规则：最后一个管理员 ----------------

    @Test
    void lastActiveAdminCannotBeDisabledLockedOrDemoted() {
        SysUser onlyAdmin = createUser("only", Roles.ADMIN);
        withOnlyActiveAdmins(List.of(onlyAdmin.getId()), () -> {
            // 通过 HTTP 时操作人本身就是可用管理员，“最后一个管理员”只会出现在并发或非 HTTP 入口（如 AI 工具）。
            // 这里构造一个具备全部管理权限但不是管理员的操作人，直接调用 Service 验证规则本身。
            AuthenticatedUser operator = syntheticOperatorWithAllPermissionsOf(onlyAdmin);
            assertRule(() -> userAdminService.changeStatus(operator, onlyAdmin.getId(), UserStatus.DISABLED, null),
                    ErrorCode.USER_LAST_ADMIN);
            assertRule(() -> userAdminService.changeStatus(operator, onlyAdmin.getId(), UserStatus.LOCKED, null),
                    ErrorCode.USER_LAST_ADMIN);
            assertRule(() -> userAdminService.assignRoles(operator, onlyAdmin.getId(), Set.of(Roles.TEACHER)),
                    ErrorCode.USER_LAST_ADMIN);
            // 保留 ADMIN、追加其他角色是允许的
            userAdminService.assignRoles(operator, onlyAdmin.getId(), Set.of(Roles.ADMIN, Roles.TEACHER));
            assertThat(userMapper.selectRoleCodes(onlyAdmin.getId())).contains(Roles.ADMIN, Roles.TEACHER);
        });
    }

    @Test
    void twoAdminsDisablingEachOtherConcurrentlyLeavesOneActive() throws Exception {
        SysUser a = createUser("mutual_a", Roles.ADMIN);
        SysUser b = createUser("mutual_b", Roles.ADMIN);
        String ta = bearer(a);
        String tb = bearer(b);
        withOnlyActiveAdmins(List.of(a.getId(), b.getId()), () -> {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Callable<Integer>> tasks = List.of(
                        () -> patchStatusRaw(ta, b.getId(), "DISABLED"),
                        () -> patchStatusRaw(tb, a.getId(), "DISABLED"));
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : pool.invokeAll(tasks)) {
                    statuses.add(f.get());
                }
                // 一方成功；另一方要么被“最后管理员”规则拒绝（422），要么因为自己已被停用而认证失败（401）
                assertThat(statuses).contains(200);
                assertThat(statuses).containsAnyOf(422, 401);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                pool.shutdown();
            }
            long active = userMapper.countActiveUsersWithRole(Roles.ADMIN);
            assertThat(active).isEqualTo(1);
        });
    }

    @Test
    void twoAdminsDemotingEachOtherConcurrentlyLeavesOneAdmin() throws Exception {
        SysUser a = createUser("demote_a", Roles.ADMIN);
        SysUser b = createUser("demote_b", Roles.ADMIN);
        AuthenticatedUser pa = principalOf(a);
        AuthenticatedUser pb = principalOf(b);
        withOnlyActiveAdmins(List.of(a.getId(), b.getId()), () -> {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Callable<String>> tasks = List.of(
                        () -> outcome(() -> userAdminService.assignRoles(pa, b.getId(), Set.of(Roles.TEACHER))),
                        () -> outcome(() -> userAdminService.assignRoles(pb, a.getId(), Set.of(Roles.TEACHER))));
                List<String> outcomes = new ArrayList<>();
                for (Future<String> f : pool.invokeAll(tasks)) {
                    outcomes.add(f.get());
                }
                assertThat(outcomes).containsExactlyInAnyOrder("OK", ErrorCode.USER_LAST_ADMIN.name());
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                pool.shutdown();
            }
            assertThat(userMapper.countActiveUsersWithRole(Roles.ADMIN)).isEqualTo(1);
        });
    }

    // ---------------- helpers ----------------

    private org.springframework.test.web.servlet.ResultActions patchStatus(SysUser operator, SysUser target,
                                                                         String status) throws Exception {
        return mvc.perform(patch("/api/v1/users/" + target.getId() + "/status").header("Authorization", bearer(operator))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", status))));
    }

    private int patchStatusRaw(String token, long targetId, String status) throws Exception {
        return mvc.perform(patch("/api/v1/users/" + targetId + "/status").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", status))))
                .andReturn().getResponse().getStatus();
    }

    private AuthenticatedUser syntheticOperatorWithAllPermissionsOf(SysUser admin) {
        SysUser teacher = createUser("operator", Roles.TEACHER);
        AuthenticatedUser adminPrincipal = principalOf(admin);
        return new AuthenticatedUser(teacher.getId(), teacher.getUsername(), "op", null,
                Set.of(Roles.TEACHER), adminPrincipal.permissions());
    }

    private static void assertRule(Runnable r, ErrorCode code) {
        assertThatThrownBy(r::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(code);
    }

    private static String outcome(Runnable r) {
        try {
            r.run();
            return "OK";
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        }
    }

    /**
     * 临时停用除 keep 以外的全部可用管理员，执行 body 后恢复，并恢复 keep 中各用户的状态与 ADMIN 角色。
     * 集成测试类按顺序执行，不会与其他测试并发。
     */
    private void withOnlyActiveAdmins(List<Long> keep, Runnable body) {
        List<Long> others = jdbc.queryForList("""
                SELECT u.id FROM sys_user u JOIN sys_user_role ur ON ur.user_id = u.id
                JOIN sys_role r ON r.id = ur.role_id WHERE r.code = 'ADMIN' AND u.status = 'ACTIVE'
                """, Long.class).stream().filter(id -> !keep.contains(id)).toList();
        others.forEach(id -> userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id).set(SysUser::getStatus, UserStatus.DISABLED)));
        try {
            body.run();
        } finally {
            others.forEach(id -> userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, id).set(SysUser::getStatus, UserStatus.ACTIVE)));
            keep.forEach(id -> {
                userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                        .eq(SysUser::getId, id).set(SysUser::getStatus, UserStatus.ACTIVE));
                userMapper.grantRole(id, Roles.ADMIN);
            });
        }
    }

    @SuppressWarnings("unused")
    private static JsonNode unused(JsonNode n) {
        return n;
    }
}
