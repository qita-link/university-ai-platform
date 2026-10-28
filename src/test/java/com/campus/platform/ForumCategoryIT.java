package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.ForumTestSupport;
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

/** 阶段五：论坛分类管理与停用语义。 */
class ForumCategoryIT extends ForumTestSupport {

    @Test
    void adminManagesCategoriesAndDuplicateNamesAreRejected() throws Exception {
        SysUser admin = createUser("forum_cat_adm", Roles.ADMIN);
        SysUser teacher = createUser("forum_cat_tea", Roles.TEACHER);
        String name = "分类" + suffix();
        long id = createCategory(admin, name);

        // 同级重名
        mvc.perform(post("/api/v1/forum/categories")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isConflict());

        // 教师可读、不可写
        MvcResult list = mvc.perform(get("/api/v1/forum/categories")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(containsCategory(body(list).get("data"), id)).isTrue();
        mvc.perform(post("/api/v1/forum/categories")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "教师分类" + suffix()))))
                .andExpect(status().isForbidden());

        // 修改
        Map<String, Object> update = new HashMap<>();
        update.put("name", name + "改");
        update.put("description", "更新后的描述");
        update.put("sortOrder", 5);
        mvc.perform(put("/api/v1/forum/categories/" + id)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(name + "改"))
                .andExpect(jsonPath("$.data.sortOrder").value(5))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 删除空分类
        mvc.perform(delete("/api/v1/forum/categories/" + id)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/forum/categories/" + id)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void disabledCategoryHidesPostsAndBlocksNewPosts() throws Exception {
        SysUser admin = createUser("forum_dis_adm", Roles.ADMIN);
        SysUser teacher = createUser("forum_dis_tea", Roles.TEACHER);
        SysUser student = createUser("forum_dis_stu", Roles.STUDENT);
        String name = "停用分类" + suffix();
        long categoryId = createCategory(admin, name);
        long postId = createPost(student, categoryId, "停用前帖子" + suffix(), "正文");

        // 停用分类
        Map<String, Object> disable = new HashMap<>();
        disable.put("name", name);
        disable.put("description", "停用测试");
        disable.put("sortOrder", 0);
        disable.put("status", "DISABLED");
        mvc.perform(put("/api/v1/forum/categories/" + categoryId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(disable)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        // 普通用户看不到分类和帖子，列表为空，也不能发帖
        mvc.perform(get("/api/v1/forum/categories/" + categoryId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isNotFound());
        assertThat(listPosts(student, "categoryId", Long.toString(categoryId)).at("/total").asLong()).isZero();
        mvc.perform(post("/api/v1/forum/posts")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(postBody(categoryId, "停用后发帖" + suffix(), "正文"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORUM_CATEGORY_DISABLED"));

        // includeDisabled 仅管理员可用
        mvc.perform(get("/api/v1/forum/categories").param("includeDisabled", "true")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
        MvcResult all = mvc.perform(get("/api/v1/forum/categories").param("includeDisabled", "true")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(containsCategory(body(all).get("data"), categoryId)).isTrue();
        assertThat(findCategory(body(all).get("data"), categoryId).get("status").asText()).isEqualTo("DISABLED");
        mvc.perform(get("/api/v1/forum/categories/" + categoryId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        // 重新启用后恢复可见
        disable.put("status", "ACTIVE");
        mvc.perform(put("/api/v1/forum/categories/" + categoryId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(disable)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk());
    }

    @Test
    void categoryDeleteIsBlockedWhilePostsExist() throws Exception {
        SysUser admin = createUser("forum_del_adm", Roles.ADMIN);
        SysUser student = createUser("forum_del_stu", Roles.STUDENT);
        long categoryId = createCategory(admin, "删除保护" + suffix());
        long postId = createPost(student, categoryId, "占用分类的帖子" + suffix(), "正文");

        mvc.perform(delete("/api/v1/forum/categories/" + categoryId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORUM_CATEGORY_IN_USE"));

        // 软删除帖子仍然占用分类（外键/追溯），分类不能删除
        mvc.perform(delete("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/forum/categories/" + categoryId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void categoryManagementRequiresPermission() throws Exception {
        SysUser admin = createUser("forum_perm_adm", Roles.ADMIN);
        SysUser teacher = createUser("forum_perm_tea", Roles.TEACHER);
        SysUser noRole = createUser("forum_perm_none");
        long categoryId = createCategory(admin, "权限分类" + suffix());

        mvc.perform(get("/api/v1/forum/categories"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/forum/categories")
                        .header("Authorization", bearer(noRole)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/forum/categories/" + categoryId)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/forum/categories/" + categoryId)
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "教师改" + suffix()))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/forum/categories/99999999")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "不存在" + suffix()))))
                .andExpect(status().isNotFound());
    }

    // ---------------- helpers ----------------

    private static boolean containsCategory(JsonNode nodes, long id) {
        return findCategory(nodes, id) != null;
    }

    private static JsonNode findCategory(JsonNode nodes, long id) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }
}
