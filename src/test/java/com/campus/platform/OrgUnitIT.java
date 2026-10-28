package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.entity.OrgStatus;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段二：组织树管理、层级/状态约束、环检测与可见性（真实 MySQL）。
 */
class OrgUnitIT extends IntegrationTestBase {

    // ---------------- 查询与创建 ----------------

    @Test
    void adminBuildsTreeThatAllAuthenticatedUsersCanRead() throws Exception {
        SysUser admin = createUser("org_adm", Roles.ADMIN);
        SysUser student = createUser("org_stu", Roles.STUDENT);
        String tag = suffix();
        long collegeId = createOrgViaApi(admin, null, "C" + tag, "计算机学院", OrgType.COLLEGE, 2);
        long departmentId = createOrgViaApi(admin, collegeId, "D" + tag, "软件工程系", OrgType.DEPARTMENT, 1);
        long majorId = createOrgViaApi(admin, departmentId, "M" + tag, "软件工程", OrgType.MAJOR, 0);
        long classId = createOrgViaApi(admin, majorId, "K" + tag, "软工一班", OrgType.CLASS, 0);

        MvcResult tree = mvc.perform(get("/api/v1/org-units/tree").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode college = findNode(body(tree).get("data"), collegeId);
        assertThat(college).isNotNull();
        assertThat(college.get("code").asText()).isEqualTo("C" + tag);
        assertThat(college.get("type").asText()).isEqualTo("COLLEGE");
        assertThat(college.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(college.get("children")).hasSize(1);
        assertThat(college.at("/children/0/id").asLong()).isEqualTo(departmentId);
        assertThat(college.at("/children/0/children/0/id").asLong()).isEqualTo(majorId);
        assertThat(college.at("/children/0/children/0/children/0/id").asLong()).isEqualTo(classId);

        mvc.perform(get("/api/v1/org-units/" + classId).header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("软工一班"))
                .andExpect(jsonPath("$.data.parentId").value(majorId));
        mvc.perform(get("/api/v1/org-units/99999999").header("Authorization", bearer(student)))
                .andExpect(status().isNotFound());
    }

    @Test
    void treeHonoursSortOrderAndFiltersDisabledNodesByDefault() throws Exception {
        SysUser admin = createUser("org_sort", Roles.ADMIN);
        SysUser student = createUser("org_sort_stu", Roles.STUDENT);
        String tag = suffix();
        long collegeId = createOrgViaApi(admin, null, "S" + tag, "排序学院", OrgType.COLLEGE, 0);
        long laterId = createOrgViaApi(admin, collegeId, "A" + tag, "后创建", OrgType.DEPARTMENT, 20);
        long earlierId = createOrgViaApi(admin, collegeId, "B" + tag, "先显示", OrgType.DEPARTMENT, 10);
        assertThat(laterId).isPositive();

        JsonNode college = findNode(treeAs(student, false), collegeId);
        assertThat(college.get("children")).hasSize(2);
        assertThat(college.at("/children/0/id").asLong()).isEqualTo(earlierId);
        assertThat(college.at("/children/1/id").asLong()).isEqualTo(laterId);
    }

    // ---------------- 校验与层级 ----------------

    @Test
    void invalidTypesCodesAndParentsAreRejected() throws Exception {
        SysUser admin = createUser("org_bad", Roles.ADMIN);
        SysUser teacher = createUser("org_bad_t", Roles.TEACHER);
        String tag = suffix();
        long collegeId = createOrgViaApi(admin, null, "V" + tag, "校验学院", OrgType.COLLEGE, 0);

        // 同级/更高级不能作为上级
        mvc.perform(post("/api/v1/org-units").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(collegeId, "X" + tag, "同级学院", OrgType.COLLEGE, 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORG_INVALID_PARENT"));

        // 编码大小写不敏感地重复
        mvc.perform(post("/api/v1/org-units").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(null, ("v" + tag).toUpperCase(), "重复编码", OrgType.COLLEGE, 0))))
                .andExpect(status().isConflict());

        // 编码格式由校验层拒绝
        mvc.perform(post("/api/v1/org-units").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(null, "bad code!", "非法编码", OrgType.COLLEGE, 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 上级不存在
        mvc.perform(post("/api/v1/org-units").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(99999999L, "P" + tag, "无上级", OrgType.DEPARTMENT, 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORG_INVALID_PARENT"));

        // 只有 org:manage 可以创建
        mvc.perform(post("/api/v1/org-units").header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(collegeId, "T" + tag, "教师创建", OrgType.DEPARTMENT, 0))))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRejectsCyclesAndTypeChangesThatBreakExistingChildren() throws Exception {
        SysUser admin = createUser("org_move", Roles.ADMIN);
        String tag = suffix();
        long collegeId = createOrgViaApi(admin, null, "MC" + tag, "移动学院", OrgType.COLLEGE, 0);
        long departmentId = createOrgViaApi(admin, collegeId, "MD" + tag, "移动系", OrgType.DEPARTMENT, 0);
        long majorId = createOrgViaApi(admin, departmentId, "MM" + tag, "移动专业", OrgType.MAJOR, 0);
        long classId = createOrgViaApi(admin, majorId, "MK" + tag, "移动班级", OrgType.CLASS, 0);

        // 不能移动到自身
        mvc.perform(put("/api/v1/org-units/" + majorId).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(majorId, "MM" + tag, "移动专业", OrgType.MAJOR, 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORG_INVALID_PARENT"));

        // 不能移动到自己的下级
        mvc.perform(put("/api/v1/org-units/" + collegeId).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(classId, "MC" + tag, "移动学院", OrgType.COLLEGE, 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORG_INVALID_PARENT"));

        // 类型变化后不能再包含原来的下级
        mvc.perform(put("/api/v1/org-units/" + departmentId).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(collegeId, "MD" + tag, "移动系", OrgType.CLASS, 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORG_INVALID_PARENT"));

        // 合法移动：专业挂到另一个系下
        long otherDepartmentId = createOrgViaApi(admin, collegeId, "OD" + tag, "另一个系", OrgType.DEPARTMENT, 0);
        mvc.perform(put("/api/v1/org-units/" + majorId).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(otherDepartmentId, "MM" + tag, "移动专业", OrgType.MAJOR, 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(otherDepartmentId));
        assertThat(orgMapper.selectById(majorId).getParentId()).isEqualTo(otherDepartmentId);
        assertThat(orgMapper.selectById(classId).getParentId()).isEqualTo(majorId);
    }

    // ---------------- 启停与可见性 ----------------

    @Test
    void disablingAndEnablingRespectParentChildInvariants() throws Exception {
        SysUser admin = createUser("org_status", Roles.ADMIN);
        SysUser student = createUser("org_status_s", Roles.STUDENT);
        String tag = suffix();
        long collegeId = createOrgViaApi(admin, null, "SC" + tag, "状态学院", OrgType.COLLEGE, 0);
        long departmentId = createOrgViaApi(admin, collegeId, "SD" + tag, "状态系", OrgType.DEPARTMENT, 0);
        long classId = createOrgViaApi(admin, departmentId, "SK" + tag, "状态班级", OrgType.CLASS, 0);

        // 有启用的下级时不能停用上级
        patchStatus(admin, collegeId, OrgStatus.DISABLED)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORG_HAS_ACTIVE_CHILDREN"));

        // 从叶子向上停用
        patchStatus(admin, classId, OrgStatus.DISABLED).andExpect(status().isOk());
        patchStatus(admin, departmentId, OrgStatus.DISABLED).andExpect(status().isOk());
        patchStatus(admin, collegeId, OrgStatus.DISABLED).andExpect(status().isOk());

        // 普通用户只能看到启用的组织；管理员可以显式包含停用组织
        assertThat(findNode(treeAs(student, false), collegeId)).isNull();
        JsonNode adminTree = treeAs(admin, true);
        assertThat(findNode(adminTree, collegeId)).isNotNull();
        assertThat(findNode(adminTree, collegeId).get("status").asText()).isEqualTo("DISABLED");
        mvc.perform(get("/api/v1/org-units/tree").param("includeDisabled", "true")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());

        // 停用组织对普通用户表现为 404
        mvc.perform(get("/api/v1/org-units/" + classId).header("Authorization", bearer(student)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/org-units/" + classId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        // 上级仍停用时不能启用下级；按从上到下恢复
        patchStatus(admin, departmentId, OrgStatus.ACTIVE)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORG_INVALID_PARENT"));
        patchStatus(admin, collegeId, OrgStatus.ACTIVE).andExpect(status().isOk());
        patchStatus(admin, departmentId, OrgStatus.ACTIVE).andExpect(status().isOk());
        patchStatus(admin, classId, OrgStatus.ACTIVE).andExpect(status().isOk());
        assertThat(orgMapper.selectById(classId).getStatus()).isEqualTo(OrgStatus.ACTIVE);
    }

    @Test
    void newUserCannotBeAssignedToDisabledOrMissingOrg() throws Exception {
        SysUser admin = createUser("org_user", Roles.ADMIN);
        SysOrgUnit disabled = createOrg(null, OrgType.COLLEGE);
        disabled.setStatus(OrgStatus.DISABLED);
        orgMapper.updateById(disabled);

        Map<String, Object> req = new HashMap<>();
        req.put("username", "u" + suffix());
        req.put("displayName", "测试用户");
        req.put("password", "Init-pass-2026");
        req.put("orgUnitId", disabled.getId());
        req.put("roleCodes", java.util.List.of(Roles.STUDENT));
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORG_UNAVAILABLE"));
    }

    // ---------------- helpers ----------------

    private long createOrgViaApi(SysUser operator, Long parentId, String code, String name, OrgType type, int sort)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/org-units").header("Authorization", bearer(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(parentId, code, name, type, sort))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(code))
                .andExpect(jsonPath("$.data.type").value(type.name()))
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions patchStatus(SysUser operator, long id, OrgStatus status)
            throws Exception {
        return mvc.perform(patch("/api/v1/org-units/" + id + "/status")
                .header("Authorization", bearer(operator))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("status", status))));
    }

    private JsonNode treeAs(SysUser user, boolean includeDisabled) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/org-units/tree")
                        .param("includeDisabled", Boolean.toString(includeDisabled))
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data");
    }

    private static Map<String, Object> payload(Long parentId, String code, String name, OrgType type, int sort) {
        Map<String, Object> body = new HashMap<>();
        body.put("parentId", parentId);
        body.put("code", code);
        body.put("name", name);
        body.put("type", type.name());
        body.put("sortOrder", sort);
        return body;
    }

    private static JsonNode findNode(JsonNode nodes, long id) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node.get("id").asLong() == id) {
                return node;
            }
            JsonNode found = findNode(node.get("children"), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
