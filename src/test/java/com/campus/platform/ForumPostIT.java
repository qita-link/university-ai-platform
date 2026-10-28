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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 阶段五：发帖、编辑、乐观锁、软删除、关键词检索、置顶与版主管理。 */
class ForumPostIT extends ForumTestSupport {

    @Test
    void studentCanCreateReadUpdateAndDeleteOwnPost() throws Exception {
        SysUser admin = createUser("forum_post_adm", Roles.ADMIN);
        SysUser author = createUser("forum_post_author", Roles.STUDENT);
        SysUser other = createUser("forum_post_other", Roles.STUDENT);
        long categoryId = createCategory(admin, "发帖" + suffix());
        String title = "第一篇帖子" + suffix();
        long postId = createPost(author, categoryId, title, "这是正文内容，包含一些说明。");

        // 其他登录用户可读
        JsonNode detail = postDetail(other, postId);
        assertThat(detail.get("title").asText()).isEqualTo(title);
        assertThat(detail.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(detail.get("version").asInt()).isZero();
        assertThat(detail.get("authorName").asText()).isEqualTo(author.getDisplayName());

        JsonNode list = listPosts(other, "categoryId", Long.toString(categoryId));
        assertThat(list.get("total").asLong()).isEqualTo(1);
        assertThat(list.at("/items/0/id").asLong()).isEqualTo(postId);
        assertThat(list.at("/items/0/excerpt").asText()).contains("正文内容");

        // 作者修改；过期 version 冲突
        Map<String, Object> update = postBody(categoryId, title + "改", "修改后的正文");
        update.put("version", 0);
        mvc.perform(put("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(title + "改"))
                .andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(put("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isConflict());

        // 他人不能改/删
        update.put("version", 1);
        mvc.perform(put("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());

        // 软删除后所有读取 404，列表不再返回
        mvc.perform(delete("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(author)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(author)))
                .andExpect(status().isNotFound());
        assertThat(listPosts(other, "categoryId", Long.toString(categoryId)).at("/total").asLong()).isZero();
        mvc.perform(put("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isNotFound());

        // 分类的已发布帖子计数同步下降
        mvc.perform(get("/api/v1/forum/categories/" + categoryId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postCount").value(0));
    }

    @Test
    void postCreateRequiresPostWriteAndValidCategory() throws Exception {
        SysUser admin = createUser("forum_pw_adm", Roles.ADMIN);
        SysUser teacher = createUser("forum_pw_tea", Roles.TEACHER);
        SysUser noRole = createUser("forum_pw_none");
        long categoryId = createCategory(admin, "发帖权限" + suffix());

        mvc.perform(get("/api/v1/forum/posts"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/forum/posts")
                        .header("Authorization", bearer(noRole)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/forum/posts")
                        .header("Authorization", bearer(noRole))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(postBody(categoryId, "无权限发帖" + suffix(), "正文"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/forum/posts")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(postBody(categoryId, "教师发帖" + suffix(), "正文"))))
                .andExpect(status().isOk());

        // 参数校验
        mvc.perform(post("/api/v1/forum/posts")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(postBody(categoryId, " ", "正文"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/forum/posts")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(postBody(categoryId, "标题", " "))))
                .andExpect(status().isBadRequest());
        Map<String, Object> noCategory = new HashMap<>();
        noCategory.put("title", "缺分类");
        noCategory.put("content", "正文");
        mvc.perform(post("/api/v1/forum/posts")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(noCategory)))
                .andExpect(status().isBadRequest());

        // 分类不存在/停用
        mvc.perform(post("/api/v1/forum/posts")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(postBody(99999999, "不存在的分类" + suffix(), "正文"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORUM_CATEGORY_DISABLED"));
    }

    @Test
    void keywordSearchFindsChineseContentInsideCategory() throws Exception {
        SysUser admin = createUser("forum_search_adm", Roles.ADMIN);
        SysUser student = createUser("forum_search_stu", Roles.STUDENT);
        long categoryA = createCategory(admin, "检索A" + suffix());
        long categoryB = createCategory(admin, "检索B" + suffix());
        String phrase = "量子研究" + suffix();
        long postA = createPost(student, categoryA, phrase + "指南", "实验室开放时间为每周三，欢迎同学预约。");
        long postB = createPost(student, categoryB, phrase + "其他", "另一个分类的帖子，不应出现在 A 的检索结果中。");

        JsonNode byTitle = listPosts(student, "categoryId", Long.toString(categoryA), "keyword", phrase);
        assertThat(byTitle.get("total").asLong()).isEqualTo(1);
        assertThat(byTitle.at("/items/0/id").asLong()).isEqualTo(postA);

        JsonNode byContent = listPosts(student, "categoryId", Long.toString(categoryA), "keyword", "实验室开放");
        assertThat(byContent.get("total").asLong()).isEqualTo(1);
        assertThat(byContent.at("/items/0/id").asLong()).isEqualTo(postA);

        // 跨分类检索会同时命中两篇
        JsonNode across = listPosts(student, "keyword", phrase);
        assertThat(across.get("total").asLong()).isGreaterThanOrEqualTo(2);

        assertThat(listPosts(student, "categoryId", Long.toString(categoryA), "keyword", "完全不存在的词")
                .at("/total").asLong()).isZero();
        assertThat(listPosts(student, "categoryId", Long.toString(categoryA), "keyword", "？")
                .at("/total").asLong()).isZero();
        // 空关键词等价于不过滤
        assertThat(listPosts(student, "categoryId", Long.toString(categoryA))
                .at("/total").asLong()).isEqualTo(1);
        // 另一分类的帖子仍然独立存在
        assertThat(postDetail(student, postB).get("categoryId").asLong()).isEqualTo(categoryB);
    }

    @Test
    void listSupportsPaginationAndPinnedOrdering() throws Exception {
        SysUser admin = createUser("forum_page_adm", Roles.ADMIN);
        SysUser author = createUser("forum_page_author", Roles.STUDENT);
        long categoryId = createCategory(admin, "分页" + suffix());
        long first = createPost(author, categoryId, "第一帖" + suffix(), "正文");
        long second = createPost(author, categoryId, "第二帖" + suffix(), "正文");
        long third = createPost(author, categoryId, "第三帖" + suffix(), "正文");

        JsonNode page1 = listPosts(author, "categoryId", Long.toString(categoryId), "page", "1", "size", "2");
        assertThat(page1.get("total").asLong()).isEqualTo(3);
        assertThat(page1.get("pages").asLong()).isEqualTo(2);
        assertThat(page1.get("items").size()).isEqualTo(2);
        assertThat(page1.at("/items/0/id").asLong()).isEqualTo(third);

        JsonNode page2 = listPosts(author, "categoryId", Long.toString(categoryId), "page", "2", "size", "2");
        assertThat(page2.get("items").size()).isEqualTo(1);
        assertThat(page2.at("/items/0/id").asLong()).isEqualTo(first);

        // 置顶帖排在最前，与发帖时间无关
        mvc.perform(patch("/api/v1/forum/posts/" + first + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pinned", true))))
                .andExpect(status().isOk());
        JsonNode pinned = listPosts(author, "categoryId", Long.toString(categoryId), "size", "10");
        assertThat(pinned.at("/items/0/id").asLong()).isEqualTo(first);
        assertThat(pinned.at("/items/0/pinned").asBoolean()).isTrue();
        assertThat(findId(pinned.get("items"), second)).isTrue();
        assertThat(findId(pinned.get("items"), third)).isTrue();

        // 取消置顶后恢复按最后活跃时间倒序
        mvc.perform(patch("/api/v1/forum/posts/" + first + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pinned", false))))
                .andExpect(status().isOk());
        assertThat(listPosts(author, "categoryId", Long.toString(categoryId))
                .at("/items/0/id").asLong()).isEqualTo(third);
    }

    @Test
    void moderatorHidesAndRestoresPostWhileAuthorStillSeesIt() throws Exception {
        SysUser admin = createUser("forum_mod_adm", Roles.ADMIN);
        SysUser teacher = createUser("forum_mod_tea", Roles.TEACHER);
        SysUser author = createUser("forum_mod_author", Roles.STUDENT);
        SysUser other = createUser("forum_mod_other", Roles.STUDENT);
        long categoryId = createCategory(admin, "隐藏" + suffix());
        long postId = createPost(author, categoryId, "会被隐藏的帖子" + suffix(), "正文");

        // 普通角色不能隐藏
        mvc.perform(patch("/api/v1/forum/posts/" + postId + "/status")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "HIDDEN"))))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/forum/posts/" + postId + "/status")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "HIDDEN"))))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/v1/forum/posts/" + postId + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "HIDDEN", "reason", "测试隐藏"))))
                .andExpect(status().isOk());

        // 其他人不可见；作者仍可读，也可在被隐藏状态下修改内容（保持 HIDDEN）
        mvc.perform(get("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        assertThat(listPosts(other, "categoryId", Long.toString(categoryId)).at("/total").asLong()).isZero();
        JsonNode hidden = postDetail(author, postId);
        assertThat(hidden.get("status").asText()).isEqualTo("HIDDEN");

        Map<String, Object> update = postBody(categoryId, "隐藏后修改" + suffix(), "作者修改说明");
        update.put("version", hidden.get("version").asInt());
        mvc.perform(put("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HIDDEN"));

        // 版主可按 HIDDEN 过滤；普通用户不行
        assertThat(listPosts(admin, "categoryId", Long.toString(categoryId), "status", "HIDDEN")
                .at("/total").asLong()).isEqualTo(1);
        mvc.perform(get("/api/v1/forum/posts").param("status", "HIDDEN")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());

        // 恢复
        mvc.perform(patch("/api/v1/forum/posts/" + postId + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "PUBLISHED"))))
                .andExpect(status().isOk());
        assertThat(postDetail(other, postId).get("status").asText()).isEqualTo("PUBLISHED");

        // 非法状态/空请求
        mvc.perform(patch("/api/v1/forum/posts/" + postId + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "DELETED"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORUM_POST_STATUS_INVALID"));
        mvc.perform(patch("/api/v1/forum/posts/" + postId + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "只有原因"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void moderatorCanEditAndDeleteAnyPost() throws Exception {
        SysUser admin = createUser("forum_any_adm", Roles.ADMIN);
        SysUser teacher = createUser("forum_any_tea", Roles.TEACHER);
        SysUser author = createUser("forum_any_author", Roles.STUDENT);
        long categoryId = createCategory(admin, "管理他人" + suffix());
        long postId = createPost(author, categoryId, "作者帖子" + suffix(), "原正文");

        Map<String, Object> update = postBody(categoryId, "管理员修改" + suffix(), "管理员编辑后的正文");
        update.put("version", 0);
        mvc.perform(put("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(update.get("title")))
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(delete("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(author)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void forumActionsAreAudited() throws Exception {
        SysUser admin = createUser("forum_audit_adm", Roles.ADMIN);
        SysUser student = createUser("forum_audit_stu", Roles.STUDENT);
        long categoryId = createCategory(admin, "审计" + suffix());
        long postId = createPost(student, categoryId, "审计帖子" + suffix(), "正文");
        mvc.perform(patch("/api/v1/forum/posts/" + postId + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "HIDDEN", "reason", "审计测试"))))
                .andExpect(status().isOk());

        MvcResult created = mvc.perform(get("/api/v1/audit-logs")
                        .param("module", "forum")
                        .param("action", "FORUM_POST_CREATE")
                        .param("targetType", "FORUM_POST")
                        .param("targetId", Long.toString(postId))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createdData = body(created).get("data");
        assertThat(createdData.get("total").asLong()).isEqualTo(1);
        assertThat(createdData.at("/items/0/result").asText()).isEqualTo("SUCCESS");
        assertThat(createdData.at("/items/0/detail").asText()).contains("审计帖子");

        MvcResult moderated = mvc.perform(get("/api/v1/audit-logs")
                        .param("module", "forum")
                        .param("action", "FORUM_POST_MODERATE")
                        .param("targetId", Long.toString(postId))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(moderated).get("data").get("total").asLong()).isEqualTo(1);

        // 审计日志只有 audit:read 可读
        mvc.perform(get("/api/v1/audit-logs")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
    }

    private static boolean findId(JsonNode items, long id) {
        for (JsonNode item : items) {
            if (item.get("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }
}
