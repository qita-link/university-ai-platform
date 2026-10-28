package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段二：管理员 / 教师 / 学生 / 无角色用户的接口访问矩阵。
 * <p>
 * 登录与密码相关接口的行为见 {@link AuthIT}，用户保护规则见 {@link UserAdminIT}；
 * 这里专注于“谁能访问哪些管理端接口”，并确认管理员路径确实可用（避免只测 403 的假阳性）。
 */
class AccessMatrixIT extends IntegrationTestBase {

    @Test
    void everyAuthenticatedRoleCanReadCommonEndpointsButNotAdminApis() throws Exception {
        SysOrgUnit college = createOrg(null, OrgType.COLLEGE);
        SysUser admin = createUser("mx_adm", Roles.ADMIN);
        SysUser teacher = createUser("mx_tea", Roles.TEACHER);
        SysUser student = createUser("mx_stu", Roles.STUDENT);
        SysUser noRole = createUser("mx_none");
        long orgId = college.getId();

        // 普通登录用户都可访问的接口
        for (SysUser user : List.of(admin, teacher, student, noRole)) {
            String token = bearer(user);
            mvc.perform(get("/api/v1/auth/me").header("Authorization", token))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/org-units/tree").header("Authorization", token))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/org-units/" + orgId).header("Authorization", token))
                    .andExpect(status().isOk());
        }

        // 无角色用户：能读公共信息，但没有任何管理权限
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(noRole)))
                .andExpect(status().isOk());
        assertForbidden(noRole, get("/api/v1/users"));
        assertForbidden(noRole, get("/api/v1/roles"));

        assertForbidden(teacher, get("/api/v1/users"));
        assertForbidden(teacher, get("/api/v1/users/" + student.getId()));
        assertForbidden(teacher, get("/api/v1/roles"));
        assertForbidden(teacher, get("/api/v1/permissions"));
        assertForbidden(teacher, get("/api/v1/audit-logs"));
        assertForbidden(teacher, get("/api/v1/org-units/tree").param("includeDisabled", "true"));

        assertForbidden(student, get("/api/v1/users"));
        assertForbidden(student, get("/api/v1/users/" + teacher.getId()));
        assertForbidden(student, get("/api/v1/roles"));
        assertForbidden(student, get("/api/v1/permissions"));
        assertForbidden(student, get("/api/v1/audit-logs"));
        assertForbidden(student, get("/api/v1/org-units/tree").param("includeDisabled", "true"));

        // 未登录一律 401（不泄露接口是否存在）
        mvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/org-units/tree")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanUseEveryManagementEndpoint() throws Exception {
        SysUser admin = createUser("mx_admin2", Roles.ADMIN);
        SysUser target = createUser("mx_target", Roles.STUDENT);
        String token = bearer(admin);

        mvc.perform(get("/api/v1/users").header("Authorization", token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/" + target.getId()).header("Authorization", token))
                .andExpect(status().isOk());

        MvcResult roles = mvc.perform(get("/api/v1/roles").header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode roleData = body(roles).get("data");
        assertThat(roleData).hasSizeGreaterThanOrEqualTo(3);
        assertThat(roleData.findValuesAsText("code")).contains(Roles.ADMIN, Roles.TEACHER, Roles.STUDENT);
        boolean adminHasPermissions = false;
        for (JsonNode role : roleData) {
            if (Roles.ADMIN.equals(role.get("code").asText())) {
                adminHasPermissions = role.get("permissions").size() > 0;
            }
        }
        assertThat(adminHasPermissions).isTrue();

        MvcResult permissions = mvc.perform(get("/api/v1/permissions").header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode permissionData = body(permissions).get("data");
        assertThat(permissionData).hasSizeGreaterThanOrEqualTo(25);
        assertThat(permissionData.findValuesAsText("code")).contains("user:manage", "org:manage", "audit:read");

        mvc.perform(get("/api/v1/audit-logs").header("Authorization", token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/org-units/tree").param("includeDisabled", "true")
                        .header("Authorization", token))
                .andExpect(status().isOk());

        // 管理员可以创建组织、创建用户、分配角色、重置密码、锁定/解锁
        String tag = suffix();
        mvc.perform(post("/api/v1/org-units").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", "MX" + tag, "name", "矩阵学院",
                                "type", "COLLEGE", "sortOrder", 0))))
                .andExpect(status().isOk());

        String username = "mxu" + suffix();
        Map<String, Object> create = new HashMap<>();
        create.put("username", username);
        create.put("displayName", "矩阵用户");
        create.put("password", "Init-pass-2026");
        create.put("roleCodes", List.of(Roles.STUDENT));
        MvcResult created = mvc.perform(post("/api/v1/users").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(create)))
                .andExpect(status().isOk())
                .andReturn();
        long createdId = body(created).at("/data/id").asLong();

        mvc.perform(put("/api/v1/users/" + createdId + "/roles").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roleCodes", List.of(Roles.STUDENT)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/users/" + createdId + "/password-reset").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("newPassword", "Reset-pass-2026"))))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/users/" + createdId + "/status").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "LOCKED", "reason", "矩阵测试"))))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/users/" + createdId + "/status").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "ACTIVE"))))
                .andExpect(status().isOk());
    }

    @Test
    void teacherAndStudentCannotPerformManagementWrites() throws Exception {
        SysUser teacher = createUser("mx_w_tea", Roles.TEACHER);
        SysUser student = createUser("mx_w_stu", Roles.STUDENT);
        SysUser target = createUser("mx_w_target", Roles.STUDENT);
        String tag = suffix();

        for (SysUser actor : List.of(teacher, student)) {
            String token = bearer(actor);
            mvc.perform(post("/api/v1/org-units").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("code", "W" + tag, "name", "越权学院",
                                    "type", "COLLEGE", "sortOrder", 0))))
                    .andExpect(status().isForbidden());

            Map<String, Object> create = new HashMap<>();
            create.put("username", "wx" + suffix());
            create.put("displayName", "越权用户");
            create.put("password", "Init-pass-2026");
            create.put("roleCodes", List.of(Roles.STUDENT));
            mvc.perform(post("/api/v1/users").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(json(create)))
                    .andExpect(status().isForbidden());

            mvc.perform(put("/api/v1/users/" + target.getId() + "/roles").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("roleCodes", List.of(Roles.TEACHER)))))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/users/" + target.getId() + "/password-reset").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("newPassword", "Reset-pass-2026"))))
                    .andExpect(status().isForbidden());
            mvc.perform(patch("/api/v1/users/" + target.getId() + "/status").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "DISABLED"))))
                    .andExpect(status().isForbidden());
        }
        assertThat(userMapper.selectById(target.getId()).getStatus().name()).isEqualTo("ACTIVE");
    }

    private void assertForbidden(SysUser user, MockHttpServletRequestBuilder request)
            throws Exception {
        mvc.perform(request.header("Authorization", bearer(user))).andExpect(status().isForbidden());
    }
}
