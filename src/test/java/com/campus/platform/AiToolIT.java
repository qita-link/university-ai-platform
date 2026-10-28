package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.CourseTestSupport;
import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段七：AI 工具调用。
 * <p>
 * 覆盖工具目录权限过滤、只读工具直接执行、写工具两阶段确认、拒绝、过期、
 * 并发确认只执行一次、业务权限仍然生效、参数校验与记录可见范围。
 */
class AiToolIT extends CourseTestSupport {

    // ------------------------------------------------------------------ 目录

    @Test
    void toolCatalogIsFilteredByPermission() throws Exception {
        SysUser student = createUser("tool_cat_stu", Roles.STUDENT);
        SysUser teacher = createUser("tool_cat_tea", Roles.TEACHER);
        SysUser noRole = createUser("tool_cat_none");

        mvc.perform(get("/api/v1/ai/tools"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ai/tools").header("Authorization", bearer(noRole)))
                .andExpect(status().isForbidden());

        JsonNode studentTools = toolCatalog(student);
        assertThat(names(studentTools)).contains("searchKnowledge", "getMySchedule", "searchAvailableCourses",
                "getCourseDetails", "searchForumPosts", "selectCourse", "dropCourse",
                "submitLeaveRequest", "getMyLeaveRequests", "createForumPost", "createForumComment");
        // 学生不能建课、不能审批请假
        assertThat(names(studentTools)).doesNotContain("createCourse", "updateCourse",
                "updateCourseSchedule", "reviewLeaveRequest");
        JsonNode selectCourse = findTool(studentTools, "selectCourse");
        assertThat(selectCourse.get("sideEffect").asBoolean()).isTrue();
        assertThat(selectCourse.get("parameters").size()).isEqualTo(1);
        assertThat(selectCourse.at("/parameters/0/name").asText()).isEqualTo("courseId");
        assertThat(selectCourse.at("/parameters/0/required").asBoolean()).isTrue();

        JsonNode teacherTools = toolCatalog(teacher);
        assertThat(names(teacherTools)).contains("createCourse", "updateCourseSchedule", "reviewLeaveRequest");
        // 教师没有 selection:select，看不到选课工具
        assertThat(names(teacherTools)).doesNotContain("selectCourse", "getMyLeaveRequests");
    }

    // ------------------------------------------------------------------ 只读

    @Test
    void readOnlyToolExecutesImmediatelyAndIsAudited() throws Exception {
        SysUser admin = createUser("tool_ro_adm", Roles.ADMIN);
        SysUser teacher = createUser("tool_ro_tea", Roles.TEACHER);
        SysUser student = createUser("tool_ro_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "TRO-" + suffix(), 10, 1, 1, 2);
        selectCourse(student, courseId);

        MvcResult result = invoke(student, "getMySchedule", Map.of("semesterId", semesterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.sideEffect").value(false))
                .andExpect(jsonPath("$.data.result.length()").value(1))
                .andExpect(jsonPath("$.data.result[0].courseId").value(courseId))
                .andExpect(jsonPath("$.data.confirmExpiresAt").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        long callId = body(result).at("/data/id").asLong();
        assertThat(body(result).at("/data/resultSummary").asText()).contains("1 条排课");

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_tool_call WHERE id = ? AND status = 'SUCCEEDED' AND side_effect = 0",
                Integer.class, callId);
        assertThat(rows).isEqualTo(1);

        // 工具调用写入 AI_TOOL 来源的审计
        MvcResult audit = mvc.perform(get("/api/v1/audit-logs")
                        .param("module", "ai")
                        .param("action", "AI_TOOL_INVOKE")
                        .param("targetId", Long.toString(callId))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode auditData = body(audit).get("data");
        assertThat(auditData.get("total").asLong()).isEqualTo(1);
        assertThat(auditData.at("/items/0/source").asText()).isEqualTo("AI_TOOL");
        assertThat(auditData.at("/items/0/detail").asText()).contains("getMySchedule");
    }

    // ------------------------------------------------------------------ 写操作

    @Test
    void writeToolNeedsConfirmationBeforeTouchingData() throws Exception {
        SysUser admin = createUser("tool_wr_adm", Roles.ADMIN);
        SysUser teacher = createUser("tool_wr_tea", Roles.TEACHER);
        SysUser student = createUser("tool_wr_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        String code = "TWR-" + suffix();
        long courseId = createOpenCourse(teacher, semesterId, code, 10, 2, 1, 2);

        MvcResult pending = invoke(student, "selectCourse", Map.of("courseId", courseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRM"))
                .andExpect(jsonPath("$.data.sideEffect").value(true))
                .andExpect(jsonPath("$.data.result").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.confirmExpiresAt").exists())
                .andReturn();
        JsonNode pendingData = body(pending).get("data");
        long callId = pendingData.get("id").asLong();
        assertThat(pendingData.get("preview").asText()).contains(code).contains("确认后占用一个名额");

        // 未确认前没有任何选课记录
        assertThat(selectionCount(courseId)).isZero();
        assertThat(courseDetail(teacher, courseId).get("selectedCount").asInt()).isZero();

        MvcResult confirmed = mvc.perform(post("/api/v1/ai/tool-calls/" + callId + "/confirm")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.status").value("SELECTED"))
                .andExpect(jsonPath("$.data.result.courseId").value(courseId))
                .andReturn();
        assertThat(body(confirmed).at("/data/resultSummary").asText()).contains("已选课");
        assertThat(selectionCount(courseId)).isEqualTo(1);
        assertThat(courseDetail(teacher, courseId).get("selectedCount").asInt()).isEqualTo(1);

        // 已确认的记录不能再次确认
        mvc.perform(post("/api/v1/ai/tool-calls/" + callId + "/confirm")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_TOOL_STATUS_INVALID"));
        assertThat(selectionCount(courseId)).isEqualTo(1);
    }

    @Test
    void rejectedToolNeverExecutes() throws Exception {
        SysUser admin = createUser("tool_rj_adm", Roles.ADMIN);
        SysUser teacher = createUser("tool_rj_tea", Roles.TEACHER);
        SysUser student = createUser("tool_rj_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "TRJ-" + suffix(), 10, 3, 1, 2);

        long callId = invokePending(student, "selectCourse", Map.of("courseId", courseId));
        mvc.perform(post("/api/v1/ai/tool-calls/" + callId + "/reject")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "不想选了"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.errorMessage").value("不想选了"));

        mvc.perform(post("/api/v1/ai/tool-calls/" + callId + "/confirm")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isConflict());
        assertThat(selectionCount(courseId)).isZero();
    }

    @Test
    void expiredConfirmationCannotExecute() throws Exception {
        SysUser admin = createUser("tool_ex_adm", Roles.ADMIN);
        SysUser teacher = createUser("tool_ex_tea", Roles.TEACHER);
        SysUser student = createUser("tool_ex_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "TEX-" + suffix(), 10, 4, 1, 2);

        long callId = invokePending(student, "selectCourse", Map.of("courseId", courseId));
        jdbc.update("UPDATE ai_tool_call SET confirm_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(1)), callId);

        mvc.perform(post("/api/v1/ai/tool-calls/" + callId + "/confirm")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_TOOL_CONFIRM_EXPIRED"));
        assertThat(callStatus(callId)).isEqualTo("EXPIRED");
        assertThat(selectionCount(courseId)).isZero();
    }

    @Test
    void concurrentConfirmationExecutesOnlyOnce() throws Exception {
        SysUser admin = createUser("tool_cc_adm", Roles.ADMIN);
        SysUser teacher = createUser("tool_cc_tea", Roles.TEACHER);
        SysUser student = createUser("tool_cc_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "TCC-" + suffix(), 10, 5, 1, 2);

        long callId = invokePending(student, "selectCourse", Map.of("courseId", courseId));
        String token = bearer(student);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/v1/ai/tool-calls/" + callId + "/confirm")
                                    .header("Authorization", token))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            int ok = 0;
            int conflict = 0;
            for (Future<Integer> future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);
                if (status == 200) {
                    ok++;
                } else if (status == 409) {
                    conflict++;
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(conflict).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(selectionCount(courseId)).isEqualTo(1);
        assertThat(callStatus(callId)).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------ 权限与失败

    @Test
    void toolPermissionsAndBusinessRulesStillApply() throws Exception {
        SysUser admin = createUser("tool_pm_adm", Roles.ADMIN);
        SysUser teacher = createUser("tool_pm_tea", Roles.TEACHER);
        SysUser otherTeacher = createUser("tool_pm_other", Roles.TEACHER);
        SysUser student = createUser("tool_pm_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "TPM-" + suffix(), 10, 6, 1, 2);
        selectCourse(student, courseId);

        // 学生没有 course:manage_own，工具目录层面就被拒绝
        mvc.perform(post("/api/v1/ai/tool-calls")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(callBody("createCourse", Map.of("semesterId", semesterId,
                                "code", "X-" + suffix(), "name", "越权建课", "credit", 3, "capacity", 10)))))
                .andExpect(status().isForbidden());

        // 教师没有 leave:submit
        mvc.perform(post("/api/v1/ai/tool-calls")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(callBody("getMyLeaveRequests", Map.of()))))
                .andExpect(status().isForbidden());

        // 非任课教师看不到该课程的请假（预览阶段就 404，不会生成待确认记录）
        long leaveId = submitLeave(student, courseId,
                java.time.LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(10, 0)),
                java.time.LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(12, 0)), "身体不适");
        mvc.perform(post("/api/v1/ai/tool-calls")
                        .header("Authorization", bearer(otherTeacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(callBody("reviewLeaveRequest", Map.of("leaveId", leaveId,
                                "decision", "APPROVED")))))
                .andExpect(status().isNotFound());

        // 任课教师可以审批：先生成待确认，确认后生效
        long reviewCall = invokePending(teacher, "reviewLeaveRequest",
                Map.of("leaveId", leaveId, "decision", "APPROVED", "comment", "同意"));
        mvc.perform(post("/api/v1/ai/tool-calls/" + reviewCall + "/confirm")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.status").value("APPROVED"));

        // 重复选课：确认阶段由业务规则拒绝，记录标记 FAILED 并保留错误码
        long duplicate = invokePending(student, "selectCourse", Map.of("courseId", courseId));
        mvc.perform(post("/api/v1/ai/tool-calls/" + duplicate + "/confirm")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELECTION_DUPLICATED"));
        assertThat(callStatus(duplicate)).isEqualTo("FAILED");
        Integer errorCode = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_tool_call WHERE id = ? AND error_code = 'SELECTION_DUPLICATED'",
                Integer.class, duplicate);
        assertThat(errorCode).isEqualTo(1);
    }

    @Test
    void argumentValidationAndUnknownToolAreRejected() throws Exception {
        SysUser student = createUser("tool_arg_stu", Roles.STUDENT);

        mvc.perform(post("/api/v1/ai/tool-calls")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(callBody("noSuchTool", Map.of()))))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/ai/tool-calls")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(callBody("selectCourse", Map.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_TOOL_ARGUMENT_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("courseId")));

        mvc.perform(post("/api/v1/ai/tool-calls")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(callBody("selectCourse", Map.of("courseId", "abc")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_TOOL_ARGUMENT_INVALID"));

        // 缺少 tool 字段
        mvc.perform(post("/api/v1/ai/tool-calls")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("arguments", Map.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void callRecordsAreScopedToOwnerUnlessAdmin() throws Exception {
        SysUser admin = createUser("tool_sc_adm", Roles.ADMIN);
        SysUser owner = createUser("tool_sc_owner", Roles.STUDENT);
        SysUser other = createUser("tool_sc_other", Roles.STUDENT);

        MvcResult result = invoke(owner, "searchForumPosts", Map.of("keyword", "不存在的关键词" + suffix()))
                .andExpect(status().isOk())
                .andReturn();
        long callId = body(result).at("/data/id").asLong();

        mvc.perform(get("/api/v1/ai/tool-calls/" + callId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/ai/tool-calls/" + callId).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ai/tool-calls/" + callId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        MvcResult otherList = mvc.perform(get("/api/v1/ai/tool-calls").header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(containsCall(body(otherList).at("/data/items"), callId)).isFalse();

        MvcResult adminList = mvc.perform(get("/api/v1/ai/tool-calls")
                        .param("userId", Long.toString(owner.getId()))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(containsCall(body(adminList).at("/data/items"), callId)).isTrue();
    }

    // ------------------------------------------------------------------ 工具方法

    private org.springframework.test.web.servlet.ResultActions invoke(SysUser user, String tool,
                                                                     Map<String, Object> arguments)
            throws Exception {
        return mvc.perform(post("/api/v1/ai/tool-calls")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(callBody(tool, arguments))));
    }

    private long invokePending(SysUser user, String tool, Map<String, Object> arguments) throws Exception {
        MvcResult result = invoke(user, tool, arguments)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRM"))
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    private JsonNode toolCatalog(SysUser user) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/ai/tools").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data");
    }

    private long selectionCount(long courseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_selection WHERE course_id = ? AND status = 'SELECTED'",
                Integer.class, courseId);
        return count == null ? 0 : count;
    }

    private String callStatus(long callId) {
        return jdbc.queryForObject("SELECT status FROM ai_tool_call WHERE id = ?", String.class, callId);
    }

    private static Map<String, Object> callBody(String tool, Map<String, Object> arguments) {
        Map<String, Object> body = new HashMap<>();
        body.put("tool", tool);
        body.put("arguments", arguments == null ? Map.of() : arguments);
        return body;
    }

    private static List<String> names(JsonNode tools) {
        List<String> names = new ArrayList<>();
        for (JsonNode tool : tools) {
            names.add(tool.get("name").asText());
        }
        return names;
    }

    private static JsonNode findTool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.get("name").asText())) {
                return tool;
            }
        }
        return null;
    }

    private static boolean containsCall(JsonNode items, long id) {
        for (JsonNode item : items) {
            if (item.get("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }
}
