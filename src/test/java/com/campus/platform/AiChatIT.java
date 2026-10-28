package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段四：AI 问答端到端（使用本地假 ChatModel，不访问外部模型）。
 */
@Import(AiChatIT.FakeChatConfig.class)
class AiChatIT extends IntegrationTestBase {

    @Test
    void chatRetrievesSourcesPersistsConversationAndCitesEvidence() throws Exception {
        SysUser admin = createUser("ai_adm", Roles.ADMIN);
        SysUser other = createUser("ai_other", Roles.STUDENT);
        long spaceId = createPublicSpace(admin, "问答" + suffix());
        createDoc(admin, spaceId, "报到指南", "# 报到\n\n报到时请携带录取通知书和身份证。");

        MvcResult first = chat(admin, Map.of("message", "报到需要带什么？", "spaceIds", List.of(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sufficientEvidence").value(true))
                .andExpect(jsonPath("$.data.answer").value("根据资料，报到时需要携带录取通知书。[1]"))
                .andExpect(jsonPath("$.data.model").value("fake-model"))
                .andExpect(jsonPath("$.data.tokenUsage.source").value("REAL"))
                .andExpect(jsonPath("$.data.citations[0].documentTitle").value("报到指南"))
                .andReturn();
        JsonNode firstData = body(first).get("data");
        long conversationId = firstData.get("conversationId").asLong();
        long userMessageId = firstData.get("userMessageId").asLong();
        long assistantMessageId = firstData.get("assistantMessageId").asLong();
        assertThat(fake.calls()).isEqualTo(1);
        assertThat(fake.lastPrompt()).contains("资料：", "录取通知书");

        Integer messages = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_message WHERE conversation_id = ?", Integer.class, conversationId);
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_retrieval_record WHERE message_id = ?", Integer.class, assistantMessageId);
        Integer citations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_message_citation WHERE message_id = ?", Integer.class, assistantMessageId);
        assertThat(messages).isEqualTo(2);
        assertThat(records).isEqualTo(1);
        assertThat(citations).isPositive();
        assertThat(userMessageId).isPositive();

        mvc.perform(get("/api/v1/ai/conversations").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(conversationId));
        mvc.perform(get("/api/v1/ai/conversations/" + conversationId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.data.messages[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.messages[1].citations.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.messages[1].citations[0].documentTitle").value("报到指南"));

        // 追问：沿用会话并再次调用模型
        chat(admin, Map.of("message", "还需要身份证吗？", "conversationId", conversationId,
                "spaceIds", List.of(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(conversationId));
        assertThat(fake.calls()).isEqualTo(2);
        mvc.perform(get("/api/v1/ai/conversations/" + conversationId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(4));

        // 无依据：不调用模型，明确返回 insufficientEvidence
        chat(admin, Map.of("message", "火星基地怎么走？", "conversationId", conversationId,
                "spaceIds", List.of(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sufficientEvidence").value(false))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("没有找到足够依据")))
                .andExpect(jsonPath("$.data.citations.length()").value(0));
        assertThat(fake.calls()).isEqualTo(2);
        Integer noEvidence = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_message WHERE conversation_id = ? AND status = 'NO_EVIDENCE'",
                Integer.class, conversationId);
        assertThat(noEvidence).isEqualTo(1);

        // 会话只能本人访问
        mvc.perform(get("/api/v1/ai/conversations/" + conversationId).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/ai/conversations/" + conversationId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/ai/conversations/" + conversationId).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ai/conversations").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void searchPermissionAndAiPermissionAreRequired() throws Exception {
        SysUser noRole = createUser("ai_norole");
        mvc.perform(post("/api/v1/ai/chat").header("Authorization", bearer(noRole))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", "你好"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/knowledge/search").header("Authorization", bearer(noRole))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("query", "报到"))))
                .andExpect(status().isForbidden());
    }

    // ---------------- helpers ----------------

    private long createPublicSpace(SysUser admin, String name) throws Exception {
        MvcResult category = mvc.perform(post("/api/v1/knowledge/categories")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "问答分类" + suffix()))))
                .andExpect(status().isOk())
                .andReturn();
        long categoryId = body(category).at("/data/id").asLong();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("name", name);
        body.put("description", "问答测试");
        body.put("visibility", "PUBLIC");
        body.put("scopeOrgUnitId", null);
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

    private org.springframework.test.web.servlet.ResultActions chat(SysUser user, Map<String, Object> request)
            throws Exception {
        return mvc.perform(post("/api/v1/ai/chat")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeChatConfig {

        @Bean
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }
    }

    @Autowired
    FakeChatModel fake;

    static class FakeChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> lastPrompt = new AtomicReference<>();

        int calls() {
            return calls.get();
        }

        String lastPrompt() {
            return lastPrompt.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            lastPrompt.set(prompt.getContents());
            return ChatResponse.builder()
                    .generations(List.of(new Generation(
                            new AssistantMessage("根据资料，报到时需要携带录取通知书。[1]"))))
                    .metadata(ChatResponseMetadata.builder()
                            .model("fake-model")
                            .usage(new DefaultUsage(120, 30))
                            .build())
                    .build();
        }
    }
}
