package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.CourseTestSupport;
import com.campus.platform.user.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段八：跨模块全链路。
 * <p>
 * 链路 A：知识库检索 → AI 问答（本地假模型）→ 论坛发帖/评论/检索。
 * 链路 B：建学期/建课/排课/开放 → 选课 → 请假审批 → AI 工具读课表 → AI 工具退课（待确认 → 确认）。
 * 目的是验证模块之间的组合行为，而不是重复各模块的单元级断言。
 */
@Import(AiChatIT.FakeChatConfig.class)
class SystemFlowIT extends CourseTestSupport {

    @Test
    void knowledgeChatAndForumChainWorksEndToEnd() throws Exception {
        SysUser admin = createUser("flow_a_adm", Roles.ADMIN);
        SysUser student = createUser("flow_a_stu", Roles.STUDENT);
        long spaceId = createPublicSpace(admin, "全链路知识库" + suffix());
        createDocument(admin, spaceId, "报到指南", "# 报到\n\n报到时请携带录取通知书和身份证。");

        // 1) 检索命中
        MvcResult search = mvc.perform(post("/api/v1/knowledge/search")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("query", "报到需要带什么", "spaceIds", List.of(spaceId)))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(search).at("/data/returnedCount").asInt()).isPositive();
        assertThat(body(search).at("/data/insufficientEvidence").asBoolean()).isFalse();

        // 2) 问答带引用，Token 来自模型返回
        MvcResult chat = mvc.perform(post("/api/v1/ai/chat")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", "报到需要带什么？", "spaceIds", List.of(spaceId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sufficientEvidence").value(true))
                .andExpect(jsonPath("$.data.citations[0].documentTitle").value("报到指南"))
                .andExpect(jsonPath("$.data.tokenUsage.source").value("REAL"))
                .andReturn();
        long conversationId = body(chat).at("/data/conversationId").asLong();
        long assistantMessageId = body(chat).at("/data/assistantMessageId").asLong();
        Integer citations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_message_citation WHERE message_id = ?", Integer.class, assistantMessageId);
        assertThat(citations).isPositive();

        // 会话可回看，且只有本人可见
        mvc.perform(get("/api/v1/ai/conversations/" + conversationId).header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(2));
        mvc.perform(get("/api/v1/ai/conversations/" + conversationId).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());

        // 3) 论坛：发帖 → 评论 → 关键词检索
        long categoryId = createForumCategory(admin, "全链路分类" + suffix());
        String keyword = "量子选课" + suffix();
        long postId = createForumPost(student, categoryId, keyword + "求助", "请问这门课难度如何？");
        long commentId = createForumComment(admin, postId, null, "可以看课程大纲。");
        mvc.perform(get("/api/v1/forum/posts/" + postId + "/comments").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].comment.id").value(commentId));

        MvcResult postSearch = mvc.perform(get("/api/v1/forum/posts")
                        .param("keyword", keyword)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(postSearch).at("/data/total").asLong()).isEqualTo(1);
        assertThat(body(postSearch).at("/data/items/0/id").asLong()).isEqualTo(postId);
    }

    @Test
    void courseLeaveAndToolChainWorksEndToEnd() throws Exception {
        SysUser admin = createUser("flow_b_adm", Roles.ADMIN);
        SysUser teacher = createUser("flow_b_tea", Roles.TEACHER);
        SysUser student = createUser("flow_b_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        String code = "FLOW-" + suffix();
        long courseId = createOpenCourse(teacher, semesterId, code, 5, 1, 1, 2);

        // 1) 学生选课（REST）
        long selectionId = selectCourse(student, courseId);
        assertThat(courseDetail(teacher, courseId).get("selectedCount").asInt()).isEqualTo(1);

        // 2) 请假 → 教师审批
        long leaveId = submitLeave(student, courseId,
                LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(10, 0)),
                LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(12, 0)), "就医");
        mvc.perform(post("/api/v1/leave-requests/" + leaveId + "/approve")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("comment", "同意"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // 3) AI 工具：读课表（只读，立即执行）
        MvcResult schedule = invokeTool(student, "getMySchedule", Map.of("semesterId", semesterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.length()").value(1))
                .andReturn();
        assertThat(body(schedule).at("/data/result/0/courseCode").asText()).isEqualTo(code);

        // 4) AI 工具：退课需要确认，确认后名额释放
        MvcResult pending = invokeTool(student, "dropCourse", Map.of("selectionId", selectionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRM"))
                .andExpect(jsonPath("$.data.preview").value(org.hamcrest.Matchers.containsString(code)))
                .andReturn();
        long callId = body(pending).at("/data/id").asLong();
        assertThat(courseDetail(teacher, courseId).get("selectedCount").asInt()).isEqualTo(1);

        mvc.perform(post("/api/v1/ai/tool-calls/" + callId + "/confirm")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        assertThat(courseDetail(teacher, courseId).get("selectedCount").asInt()).isZero();
        assertThat(mySelections(student).size()).isZero();

        // 5) 审计：工具确认与业务动作都能按目标追溯
        MvcResult toolAudit = mvc.perform(get("/api/v1/audit-logs")
                        .param("module", "ai")
                        .param("action", "AI_TOOL_CONFIRM")
                        .param("targetId", Long.toString(callId))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(toolAudit).at("/data/total").asLong()).isEqualTo(1);
        assertThat(body(toolAudit).at("/data/items/0/source").asText()).isEqualTo("AI_TOOL");

        MvcResult businessAudit = mvc.perform(get("/api/v1/audit-logs")
                        .param("module", "course")
                        .param("action", "COURSE_SELECTION_DROP")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(businessAudit).at("/data/total").asLong()).isPositive();
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions invokeTool(SysUser user, String tool, Map<String, Object> arguments) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("tool", tool);
        body.put("arguments", arguments);
        return mvc.perform(post("/api/v1/ai/tool-calls")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private long createPublicSpace(SysUser admin, String name) throws Exception {
        MvcResult category = mvc.perform(post("/api/v1/knowledge/categories")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "全链路分类" + suffix()))))
                .andExpect(status().isOk())
                .andReturn();
        long categoryId = body(category).at("/data/id").asLong();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("name", name);
        body.put("description", "全链路测试");
        body.put("visibility", "PUBLIC");
        body.put("scopeOrgUnitId", null);
        MvcResult space = mvc.perform(post("/api/v1/knowledge/spaces")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return body(space).at("/data/id").asLong();
    }

    private void createDocument(SysUser admin, long spaceId, String title, String content) throws Exception {
        mvc.perform(post("/api/v1/knowledge/spaces/" + spaceId + "/documents")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", title, "content", content))))
                .andExpect(status().isOk());
    }

    private long createForumCategory(SysUser admin, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/forum/categories")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "sortOrder", 0))))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    private long createForumPost(SysUser author, long categoryId, String title, String content) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/forum/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("categoryId", categoryId, "title", title, "content", content))))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    private long createForumComment(SysUser author, long postId, Long parentId, String content) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        MvcResult result = mvc.perform(post("/api/v1/forum/posts/" + postId + "/comments")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).at("/data/id").asLong();
    }
}
