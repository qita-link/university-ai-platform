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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段三：知识库分类、空间、可见范围与成员授权。
 */
class KnowledgeSpaceIT extends IntegrationTestBase {

    @Test
    void categoriesAreReadableByAllButWritableOnlyByAdmin() throws Exception {
        SysUser admin = createUser("kb_cat_adm", Roles.ADMIN);
        SysUser teacher = createUser("kb_cat_tea", Roles.TEACHER);
        String name = "分类" + suffix();

        MvcResult created = mvc.perform(post("/api/v1/knowledge/categories")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "description", "测试分类", "sortOrder", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(name))
                .andReturn();
        long categoryId = body(created).at("/data/id").asLong();

        mvc.perform(post("/api/v1/knowledge/categories").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isConflict());

        MvcResult tree = mvc.perform(get("/api/v1/knowledge/categories")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(findCategory(body(tree).get("data"), categoryId)).isNotNull();

        mvc.perform(post("/api/v1/knowledge/categories").header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "教师分类" + suffix()))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/knowledge/categories/" + categoryId)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicOrgAndPrivateSpacesRespectVisibilityAndMembership() throws Exception {
        SysUser admin = createUser("kb_vis_adm", Roles.ADMIN);
        SysOrgUnit orgA = createOrg(null, OrgType.COLLEGE);
        SysOrgUnit orgB = createOrg(null, OrgType.COLLEGE);
        SysUser teacherA = createUserInOrg("kb_vis_tea", orgA.getId(), Roles.TEACHER);
        SysUser studentA = createUserInOrg("kb_vis_sa", orgA.getId(), Roles.STUDENT);
        SysUser studentB = createUserInOrg("kb_vis_sb", orgB.getId(), Roles.STUDENT);
        long categoryId = createCategory(admin, "可见性" + suffix());

        String publicName = "公开库" + suffix();
        long publicId = createSpace(admin, categoryId, publicName, "PUBLIC", null);
        assertThat(spaceKeyword(studentB, publicName).at("/total").asLong()).isEqualTo(1);
        mvc.perform(get("/api/v1/knowledge/spaces/" + publicId).header("Authorization", bearer(studentB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.data.accessLevel").value("PUBLIC"));

        String orgName = "组织库" + suffix();
        long orgSpaceId = createSpace(teacherA, categoryId, orgName, "ORG", orgA.getId());
        assertThat(spaceKeyword(studentA, orgName).at("/total").asLong()).isEqualTo(1);
        assertThat(spaceKeyword(studentB, orgName).at("/total").asLong()).isZero();
        mvc.perform(get("/api/v1/knowledge/spaces/" + orgSpaceId).header("Authorization", bearer(studentB)))
                .andExpect(status().isNotFound());

        String privateName = "私有库" + suffix();
        long privateId = createSpace(teacherA, categoryId, privateName, "PRIVATE", null);
        assertThat(spaceKeyword(studentA, privateName).at("/total").asLong()).isZero();
        mvc.perform(post("/api/v1/knowledge/spaces/" + privateId + "/members")
                        .header("Authorization", bearer(teacherA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", studentA.getId(), "memberRole", "VIEWER"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/knowledge/spaces/" + privateId).header("Authorization", bearer(studentA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessLevel").value("VIEWER"));
        mvc.perform(get("/api/v1/knowledge/spaces/" + privateId).header("Authorization", bearer(studentB)))
                .andExpect(status().isNotFound());

        // 教师不能创建公开库；课程范围在阶段六前拒绝
        mvc.perform(post("/api/v1/knowledge/spaces").header("Authorization", bearer(teacherA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(spaceBody(categoryId, "教师公开" + suffix(), "PUBLIC", null))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/knowledge/spaces").header("Authorization", bearer(teacherA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(spaceBody(categoryId, "课程库" + suffix(), "COURSE", null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("KB_SPACE_INVALID_VISIBILITY"));
    }

    @Test
    void spaceUpdateUsesVersionAndOwnerAccessRules() throws Exception {
        SysOrgUnit org = createOrg(null, OrgType.COLLEGE);
        SysUser teacher = createUserInOrg("kb_upd_tea", org.getId(), Roles.TEACHER);
        SysUser otherTeacher = createUserInOrg("kb_upd_other", org.getId(), Roles.TEACHER);
        SysUser viewer = createUserInOrg("kb_upd_view", org.getId(), Roles.STUDENT);
        long categoryId = createCategory(createUser("kb_upd_adm", Roles.ADMIN), "更新" + suffix());

        long spaceId = createSpace(teacher, categoryId, "更新库" + suffix(), "PRIVATE", null);
        Map<String, Object> update = new HashMap<>();
        update.put("categoryId", categoryId);
        update.put("name", "更新后的库");
        update.put("description", "更新说明");
        update.put("visibility", "PRIVATE");
        update.put("scopeOrgUnitId", null);
        update.put("version", 0);
        mvc.perform(put("/api/v1/knowledge/spaces/" + spaceId).header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("更新后的库"))
                .andExpect(jsonPath("$.data.version").value(1));
        // 过期 version
        mvc.perform(put("/api/v1/knowledge/spaces/" + spaceId).header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isConflict());
        // 非成员看不到私有库
        mvc.perform(put("/api/v1/knowledge/spaces/" + spaceId).header("Authorization", bearer(otherTeacher))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isNotFound());

        // 只读成员可以读，但不能管理
        mvc.perform(post("/api/v1/knowledge/spaces/" + spaceId + "/members")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", viewer.getId(), "memberRole", "VIEWER"))))
                .andExpect(status().isOk());
        update.put("version", 1);
        mvc.perform(put("/api/v1/knowledge/spaces/" + spaceId).header("Authorization", bearer(viewer))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberManagementProtectsOwnerAndSupportsRoles() throws Exception {
        SysOrgUnit org = createOrg(null, OrgType.COLLEGE);
        SysUser owner = createUserInOrg("kb_mem_owner", org.getId(), Roles.TEACHER);
        SysUser editor = createUserInOrg("kb_mem_editor", org.getId(), Roles.TEACHER);
        SysUser viewer = createUserInOrg("kb_mem_viewer", org.getId(), Roles.STUDENT);
        long categoryId = createCategory(createUser("kb_mem_adm", Roles.ADMIN), "成员" + suffix());
        long spaceId = createSpace(owner, categoryId, "成员库" + suffix(), "PRIVATE", null);

        mvc.perform(post("/api/v1/knowledge/spaces/" + spaceId + "/members")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", editor.getId(), "memberRole", "EDITOR"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/knowledge/spaces/" + spaceId + "/members")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", viewer.getId(), "memberRole", "VIEWER"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId + "/members")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        mvc.perform(delete("/api/v1/knowledge/spaces/" + spaceId + "/members/" + owner.getId())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/knowledge/spaces/" + spaceId + "/members/" + owner.getId())
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("memberRole", "VIEWER"))))
                .andExpect(status().isConflict());

        // 添加不存在的用户
        mvc.perform(post("/api/v1/knowledge/spaces/" + spaceId + "/members")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", 99999999, "memberRole", "VIEWER"))))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/knowledge/spaces/" + spaceId + "/members/" + viewer.getId())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId).header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound());
    }

    // ---------------- helpers ----------------

    private long createCategory(SysUser admin, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/knowledge/categories")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    private long createSpace(SysUser operator, long categoryId, String name, String visibility, Long orgId)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/knowledge/spaces")
                        .header("Authorization", bearer(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(spaceBody(categoryId, name, visibility, orgId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(name))
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    private static Map<String, Object> spaceBody(long categoryId, String name, String visibility, Long orgId) {
        Map<String, Object> body = new HashMap<>();
        body.put("categoryId", categoryId);
        body.put("name", name);
        body.put("description", "测试知识库");
        body.put("visibility", visibility);
        body.put("scopeOrgUnitId", orgId);
        return body;
    }

    private JsonNode spaceKeyword(SysUser user, String keyword) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/knowledge/spaces")
                        .param("keyword", keyword)
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data");
    }

    private static JsonNode findCategory(JsonNode nodes, long id) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node.get("id").asLong() == id) {
                return node;
            }
            JsonNode child = findCategory(node.get("children"), id);
            if (child != null) {
                return child;
            }
        }
        return null;
    }
}
