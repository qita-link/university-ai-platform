package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段四：MySQL ngram 关键词检索、标题加权、去重、阈值与空间权限。
 */
class KnowledgeSearchIT extends IntegrationTestBase {

    @Test
    void searchesWithinAccessibleSpaceAndReturnsSourceMetadata() throws Exception {
        SysUser admin = createUser("search_adm", Roles.ADMIN);
        SysUser student = createUser("search_stu", Roles.STUDENT);
        long spaceId = createPublicSpace(admin, "检索" + suffix());
        createDoc(admin, spaceId, "报到指南", "# 新生报到\n\n报到时请携带录取通知书和身份证。");
        createDoc(admin, spaceId, "校园生活", "# 食堂\n\n食堂开放时间和位置。");

        MvcResult result = search(student, Map.of("query", "报到", "spaceIds", List.of(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insufficientEvidence").value(false))
                .andReturn();
        JsonNode data = body(result).get("data");
        assertThat(data.get("strategy").asText()).isEqualTo("KEYWORD");
        assertThat(data.get("candidateCount").asInt()).isPositive();
        assertThat(data.get("hits")).hasSize(1);
        JsonNode hit = data.at("/hits/0");
        assertThat(hit.get("documentTitle").asText()).isEqualTo("报到指南");
        assertThat(hit.get("headingPath").asText()).contains("新生报到");
        assertThat(hit.get("snippet").asText()).contains("录取通知书");
        assertThat(hit.get("score").asDouble()).isPositive();
        assertThat(hit.get("spaceName").asText()).isNotBlank();

        // 多关键词 OR 检索可命中
        search(admin, Map.of("query", "录取 身份证", "spaceIds", List.of(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits.length()").value(1));
        // 不存在的词/空操作符 -> 明确无依据
        search(admin, Map.of("query", "火星基地", "spaceIds", List.of(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insufficientEvidence").value(true))
                .andExpect(jsonPath("$.data.hits.length()").value(0));
        search(admin, Map.of("query", "+++---", "spaceIds", List.of(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insufficientEvidence").value(true));
        // 高阈值过滤
        search(admin, Map.of("query", "报到", "spaceIds", List.of(spaceId), "minScore", 100000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits.length()").value(0));
    }

    @Test
    void titleMatchRanksAboveContentOnlyMatch() throws Exception {
        SysUser admin = createUser("search_rank", Roles.ADMIN);
        long spaceId = createPublicSpace(admin, "排序" + suffix());
        createDoc(admin, spaceId, "奖学金", "# 奖学金\n\n奖学金申请条件和材料。");
        createDoc(admin, spaceId, "其他通知", "# 其他通知\n\n这里提到奖学金，但不是主题。");

        MvcResult result = search(admin, Map.of("query", "奖学金", "spaceIds", List.of(spaceId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode hits = body(result).at("/data/hits");
        assertThat(hits).hasSizeGreaterThanOrEqualTo(2);
        assertThat(hits.get(0).get("documentTitle").asText()).isEqualTo("奖学金");
        assertThat(hits.get(0).get("score").asDouble()).isGreaterThan(hits.get(1).get("score").asDouble());
    }

    @Test
    void duplicateContentIsDeduplicated() throws Exception {
        SysUser admin = createUser("search_dup", Roles.ADMIN);
        long spaceId = createPublicSpace(admin, "去重" + suffix());
        String content = "# 重复通知\n\n同一份通知内容，用于验证去重。";
        createDoc(admin, spaceId, "重复通知A", content);
        createDoc(admin, spaceId, "重复通知B", content);

        search(admin, Map.of("query", "重复通知", "spaceIds", List.of(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateCount").value(2))
                .andExpect(jsonPath("$.data.hits.length()").value(1));
    }

    @Test
    void privateSpaceContentIsNotSearchableUntilMembership() throws Exception {
        SysUser admin = createUser("search_priv", Roles.ADMIN);
        SysUser student = createUser("search_priv_stu", Roles.STUDENT);
        long spaceId = createPrivateSpace(admin, "私有检索" + suffix());
        createDoc(admin, spaceId, "内部资料", "# 绝密\n\n绝密内部资料不可被普通学生检索到。");

        search(student, Map.of("query", "绝密", "spaceIds", List.of(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits.length()").value(0));
        search(student, Map.of("query", "绝密"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits.length()").value(0));

        mvc.perform(post("/api/v1/knowledge/spaces/" + spaceId + "/members")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", student.getId(), "memberRole", "VIEWER"))))
                .andExpect(status().isOk());
        search(student, Map.of("query", "绝密", "spaceIds", List.of(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits.length()").value(1));
    }

    // ---------------- helpers ----------------

    private long createPublicSpace(SysUser admin, String name) throws Exception {
        return createSpace(admin, name, "PUBLIC", null);
    }

    private long createPrivateSpace(SysUser admin, String name) throws Exception {
        return createSpace(admin, name, "PRIVATE", null);
    }

    private long createSpace(SysUser admin, String name, String visibility, Long orgId) throws Exception {
        MvcResult category = mvc.perform(post("/api/v1/knowledge/categories")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "检索分类" + suffix()))))
                .andExpect(status().isOk())
                .andReturn();
        long categoryId = body(category).at("/data/id").asLong();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("name", name);
        body.put("description", "检索测试");
        body.put("visibility", visibility);
        body.put("scopeOrgUnitId", orgId);
        MvcResult space = mvc.perform(post("/api/v1/knowledge/spaces")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return body(space).at("/data/id").asLong();
    }

    private void createDoc(SysUser admin, long spaceId, String title, String content) throws Exception {
        mvc.perform(post("/api/v1/knowledge/spaces/" + spaceId + "/documents")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", title, "content", content))))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions search(SysUser user, Map<String, Object> request)
            throws Exception {
        return mvc.perform(post("/api/v1/knowledge/search")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)));
    }
}
