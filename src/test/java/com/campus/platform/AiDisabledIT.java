package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.entity.SysUser;
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
 * 未配置模型时不伪造回答：有检索结果但无 ChatModel 应返回 503，并保留失败消息。
 */
class AiDisabledIT extends IntegrationTestBase {

    @Test
    void missingChatModelReturnsServiceUnavailableAndDoesNotFabricateAnswer() throws Exception {
        SysUser admin = createUser("ai_dis_adm", Roles.ADMIN);
        long spaceId = createSpace(admin, "未启用" + suffix());
        createDoc(admin, spaceId, "报到指南", "# 报到\n\n报到时请携带录取通知书和身份证。");

        MvcResult result = mvc.perform(post("/api/v1/ai/chat")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", "报到带什么？", "spaceIds", List.of(spaceId)))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andReturn();
        assertThat(body(result).get("message").asText()).contains("AI");

        Integer failed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_message m
                  JOIN ai_conversation c ON c.id = m.conversation_id
                 WHERE c.user_id = ? AND m.status = 'FAILED' AND m.error_code = 'AI_NOT_CONFIGURED'
                """, Integer.class, admin.getId());
        assertThat(failed).isEqualTo(1);
        Integer citations = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_message_citation cit
                  JOIN ai_message m ON m.id = cit.message_id
                  JOIN ai_conversation c ON c.id = m.conversation_id
                 WHERE c.user_id = ?
                """, Integer.class, admin.getId());
        assertThat(citations).isZero();
    }

    private long createSpace(SysUser admin, String name) throws Exception {
        MvcResult category = mvc.perform(post("/api/v1/knowledge/categories")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "未启用分类" + suffix()))))
                .andExpect(status().isOk())
                .andReturn();
        long categoryId = body(category).at("/data/id").asLong();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("categoryId", categoryId);
        request.put("name", name);
        request.put("description", "AI 未启用测试");
        request.put("visibility", "PUBLIC");
        MvcResult space = mvc.perform(post("/api/v1/knowledge/spaces")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
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
}
