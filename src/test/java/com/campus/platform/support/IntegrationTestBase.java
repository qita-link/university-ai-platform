package com.campus.platform.support;

import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.jwt.JwtTokenService;
import com.campus.platform.user.entity.OrgStatus;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.entity.UserStatus;
import com.campus.platform.user.mapper.SysOrgUnitMapper;
import com.campus.platform.user.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashSet;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：真实 MySQL 8 + 完整 Spring 上下文 + MockMvc。
 * <p>
 * 测试上下文启动时先 clean 再 migrate，保证每次测试运行都从空库按迁移脚本重新初始化
 * （同时验证迁移脚本可在新环境中重复执行）。仅 test profile 允许 clean。
 * 各测试类共享同一个上下文和数据库，因此测试数据一律使用随机后缀，断言不依赖全表计数。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTestBase.CleanMigrateConfig.class)
public abstract class IntegrationTestBase {

    /** createUser 创建的用户的默认密码 */
    protected static final String DEFAULT_PASSWORD = "Passw0rd!Test";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected SysUserMapper userMapper;

    @Autowired
    protected SysOrgUnitMapper orgMapper;

    @Autowired
    protected JwtTokenService jwtTokenService;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    /** 创建一个指定角色的 ACTIVE 用户，用户名带随机后缀避免测试间冲突。 */
    protected SysUser createUser(String usernamePrefix, String... roleCodes) {
        return createUserInOrg(usernamePrefix, null, roleCodes);
    }

    protected SysUser createUserInOrg(String usernamePrefix, Long orgUnitId, String... roleCodes) {
        SysUser u = new SysUser();
        u.setUsername(usernamePrefix + "_" + suffix());
        u.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        u.setDisplayName(usernamePrefix);
        u.setOrgUnitId(orgUnitId);
        u.setStatus(UserStatus.ACTIVE);
        u.setTokenVersion(0);
        u.setFailedLoginCount(0);
        userMapper.insert(u);
        for (String role : roleCodes) {
            userMapper.grantRole(u.getId(), role);
        }
        return u;
    }

    protected SysOrgUnit createOrg(Long parentId, OrgType type) {
        SysOrgUnit o = new SysOrgUnit();
        o.setParentId(parentId);
        o.setCode(type.name() + "-" + suffix());
        o.setName(type.label() + suffix());
        o.setType(type);
        o.setStatus(OrgStatus.ACTIVE);
        o.setSortOrder(0);
        orgMapper.insert(o);
        return o;
    }

    /** 以数据库中的最新状态构造当前用户（直接调用 Service 时使用）。 */
    protected AuthenticatedUser principalOf(SysUser u) {
        SysUser fresh = userMapper.selectById(u.getId());
        return new AuthenticatedUser(fresh.getId(), fresh.getUsername(), fresh.getDisplayName(),
                fresh.getOrgUnitId(), new HashSet<>(userMapper.selectRoleCodes(fresh.getId())),
                new HashSet<>(userMapper.selectPermissionCodes(fresh.getId())));
    }

    /** 按数据库中的最新 token_version 签发令牌。 */
    protected String bearer(SysUser u) {
        SysUser fresh = userMapper.selectById(u.getId());
        return "Bearer " + jwtTokenService.issueAccessToken(fresh.getId(), fresh.getUsername(),
                fresh.getTokenVersion()).accessToken();
    }

    protected String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    /** 调用登录接口，返回原始结果（不断言状态）。 */
    protected MvcResult login(String username, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new java.util.LinkedHashMap<>(java.util.Map.of(
                                "username", username, "password", password)))))
                .andReturn();
    }

    protected static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** 每个测试使用不同的“客户端 IP”，避免共享上下文中的限流桶互相影响。 */
    protected static String randomIp() {
        int n = (int) (Math.random() * 0xFFFFFF);
        return "10." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }

    protected static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CleanMigrateConfig {
        @Bean
        FlywayMigrationStrategy cleanMigrateStrategy() {
            return (Flyway flyway) -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }
}
