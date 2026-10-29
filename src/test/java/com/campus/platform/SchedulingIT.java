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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自动排课模块集成测试（真实 MySQL + 完整 Spring 上下文 + MockMvc）。
 * <p>
 * 覆盖：基础数据、预检、异步求解、独立复核、调整冲突、锁定、发布、本人课表、权限与重复启动。
 * 求解结果不要求唯一，只断言硬约束满足与业务状态。
 */
class SchedulingIT extends IntegrationTestBase {

    // ------------------------------------------------------------------ 主流程

    @Test
    void solvesValidatesAdjustsAndPublishes() throws Exception {
        SysUser admin = createUser("sch_adm", Roles.ADMIN);
        SysUser teacher = createUser("sch_tea", Roles.TEACHER);
        SysOrgUnit clazz = createOrg(null, OrgType.CLASS);
        Fixture f = fixture(admin, teacher, clazz, 60, 64);

        // 预检通过
        JsonNode pre = ok(get("/api/v1/scheduling/teaching-tasks/precheck?semesterId=" + f.semesterId),
                admin);
        assertThat(pre.path("errorCount").asInt()).isZero();
        assertThat(pre.path("solvable").asBoolean()).isTrue();

        // 求解
        long jobId = createJob(admin, f.semesterId, "全量排课");
        MvcResult run = mvc.perform(post("/api/v1/scheduling/jobs/" + jobId + "/run")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(run).path("data").path("status").asText()).isIn("QUEUED", "RUNNING");

        JsonNode status = awaitTerminal(admin, jobId);
        assertThat(status.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(status.path("solverStatus").asText()).isIn("OPTIMAL", "FEASIBLE");

        JsonNode job = ok(get("/api/v1/scheduling/jobs/" + jobId), admin);
        long versionId = job.path("resultVersionId").asLong();
        assertThat(versionId).isPositive();

        // 独立复核：0 条硬约束违反，4 次课全部安排
        JsonNode evaluation = ok(get("/api/v1/scheduling/versions/" + versionId + "/evaluation"), admin);
        assertThat(evaluation.path("hardViolations").asInt()).isZero();
        assertThat(evaluation.path("placedSessions").asInt()).isEqualTo(2);
        assertThat(evaluation.path("totalSessions").asInt()).isEqualTo(2);
        assertThat(evaluation.path("score").asDouble()).isBetween(0.0, 100.0);

        JsonNode entries = ok(get("/api/v1/scheduling/versions/" + versionId + "/entries"), admin);
        assertThat(entries.size()).isEqualTo(2);
        long firstId = entries.get(0).path("id").asLong();
        int firstDay = entries.get(0).path("dayOfWeek").asInt();
        int firstStart = entries.get(0).path("startPeriod").asInt();

        // 移到另一节课已占用的位置 → 409
        JsonNode second = entries.get(1);
        mvc.perform(put("/api/v1/scheduling/versions/" + versionId + "/entries/" + second.path("id").asLong())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dayOfWeek", firstDay, "startPeriod", firstStart,
                                "reason", "冲突测试"))))
                .andExpect(status().isConflict());

        // 合法移动：找该任务尚未使用的一天，2 节连堂 → 第 7 节
        long taskIdOfFirst = entries.get(0).path("taskId").asLong();
        java.util.Set<Integer> usedDays = new java.util.HashSet<>();
        for (JsonNode e : entries) {
            if (e.path("taskId").asLong() == taskIdOfFirst) {
                usedDays.add(e.path("dayOfWeek").asInt());
            }
        }
        int freeDay = 1;
        for (int d = 1; d <= 5; d++) {
            if (!usedDays.contains(d)) {
                freeDay = d;
                break;
            }
        }
        JsonNode moved = putJson("/api/v1/scheduling/versions/" + versionId + "/entries/" + firstId,
                Map.of("dayOfWeek", freeDay, "startPeriod", 7, "reason", "调整到空闲日"), admin);
        assertThat(moved.path("dayOfWeek").asInt()).isEqualTo(freeDay);
        assertThat(moved.path("startPeriod").asInt()).isEqualTo(7);
        assertThat(moved.path("endPeriod").asInt()).isEqualTo(8);

        // 锁定后再移动 → 409（未加 force）
        putJson("/api/v1/scheduling/versions/" + versionId + "/entries/" + firstId + "/lock",
                Map.of("locked", true, "reason", "锁定"), admin);
        mvc.perform(put("/api/v1/scheduling/versions/" + versionId + "/entries/" + firstId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dayOfWeek", 1, "startPeriod", 1))))
                .andExpect(status().isConflict());

        // 复核 + 发布
        JsonNode checked = ok(post("/api/v1/scheduling/versions/" + versionId + "/check"), admin);
        assertThat(checked.path("hardViolations").asInt()).isZero();
        JsonNode published = postJson("/api/v1/scheduling/versions/" + versionId + "/publish",
                Map.of("reason", "集成测试发布"), admin);
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");

        // 已发布版本不能直接调整
        mvc.perform(put("/api/v1/scheduling/versions/" + versionId + "/entries/" + firstId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dayOfWeek", 2, "startPeriod", 3))))
                .andExpect(status().isConflict());

        // 发布记录
        JsonNode records = ok(get("/api/v1/scheduling/publish-records?semesterId=" + f.semesterId), admin);
        assertThat(records.size()).isGreaterThanOrEqualTo(1);
        assertThat(records.get(0).path("action").asText()).isEqualTo("PUBLISH");

        // 教师本人课表
        JsonNode mine = ok(get("/api/v1/scheduling/timetables/me?semesterId=" + f.semesterId), teacher);
        assertThat(mine.path("scope").asText()).isEqualTo("TEACHER");
        int cells = 0;
        for (JsonNode row : mine.path("rows")) {
            for (JsonNode cell : row.path("cells")) {
                cells += cell.path("entries").size();
            }
        }
        assertThat(cells).isEqualTo(4); // 2 次课 × 每次 2 节，在网格中出现 4 个格子

        // 教师教多个班时必须能从课表区分班级：每个课块都要带行政班名
        int withClass = 0;
        for (JsonNode row : mine.path("rows")) {
            for (JsonNode cell : row.path("cells")) {
                for (JsonNode e : cell.path("entries")) {
                    assertThat(e.path("classNames").size()).as("课块缺少班级名").isPositive();
                    assertThat(e.path("classNames").get(0).asText()).isEqualTo(clazz.getName());
                    withClass++;
                }
            }
        }
        assertThat(withClass).isEqualTo(4);

        // 不传学期也要能拿到：回落到最近一次发布，而不是 404
        JsonNode latest = ok(get("/api/v1/scheduling/timetables/me"), teacher);
        assertThat(latest.path("scope").asText()).isEqualTo("TEACHER");

        // 学生只看已发布课表：把学生挂到该行政班后可见
        SysUser student = createUserInOrg("sch_stu", clazz.getId(), Roles.STUDENT);
        JsonNode studentView = ok(get("/api/v1/scheduling/timetables/me?semesterId=" + f.semesterId), student);
        assertThat(studentView.path("scope").asText()).isEqualTo("CLASS");

        // CSV 导出
        MvcResult csv = mvc.perform(get("/api/v1/scheduling/versions/" + versionId + "/export.csv")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn();
        String text = csv.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("学期,版本,课程").contains("Java 程序设计");

        // 复制版本 → 新草稿，可用于下一轮调整
        JsonNode copy = postJson("/api/v1/scheduling/versions/" + versionId + "/copy",
                Map.of("name", "下学期候选课表"), admin);
        assertThat(copy.path("status").asText()).isEqualTo("DRAFT");
        assertThat(copy.path("baseVersionId").asLong()).isEqualTo(versionId);
    }

    // ------------------------------------------------------------------ 预检阻止求解

    @Test
    void inconsistentHoursAreRejectedBeforeSolving() throws Exception {
        SysUser admin = createUser("sch_adm2", Roles.ADMIN);
        SysUser teacher = createUser("sch_tea2", Roles.TEACHER);
        SysOrgUnit clazz = createOrg(null, OrgType.CLASS);
        Fixture f = fixture(admin, teacher, clazz, 60, 64);

        // 把任务学时改小 → 与 2 次/周 × 2 节 × 16 周 = 64 不符
        JsonNode task = ok(get("/api/v1/scheduling/teaching-tasks?semesterId=" + f.semesterId
                + "&size=1"), admin).path("items").get(0);
        Map<String, Object> update = taskBody(f, task.path("code").asText(), teacher.getId(), 30);
        update.put("version", task.path("version").asInt());
        mvc.perform(put("/api/v1/scheduling/teaching-tasks/" + task.path("id").asLong())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk());

        JsonNode pre = ok(get("/api/v1/scheduling/teaching-tasks/precheck?semesterId=" + f.semesterId), admin);
        assertThat(pre.path("solvable").asBoolean()).isFalse();
        assertThat(pre.path("errorCount").asInt()).isGreaterThan(0);
        List<String> codes = new ArrayList<>();
        pre.path("issues").forEach(i -> codes.add(i.path("code").asText()));
        assertThat(codes).contains("HOURS_CONSISTENCY");

        long jobId = createJob(admin, f.semesterId, "学时不一致");
        mvc.perform(post("/api/v1/scheduling/jobs/" + jobId + "/run")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        JsonNode status = awaitTerminal(admin, jobId);
        assertThat(status.path("status").asText()).isEqualTo("FAILED");
        assertThat(status.path("message").asText()).contains("预检");

        JsonNode diagnostics = ok(get("/api/v1/scheduling/jobs/" + jobId + "/diagnostics"), admin);
        assertThat(diagnostics.path("issues").size()).isGreaterThan(0);
    }

    // ------------------------------------------------------------------ 容量不足

    @Test
    void classLargerThanRoomsIsReportedWithReason() throws Exception {
        SysUser admin = createUser("sch_adm3", Roles.ADMIN);
        SysUser teacher = createUser("sch_tea3", Roles.TEACHER);
        SysOrgUnit clazz = createOrg(null, OrgType.CLASS);
        Fixture f = fixture(admin, teacher, clazz, 20, 64, 5000); // 教室 20 座，班级 5000 人

        JsonNode pre = ok(get("/api/v1/scheduling/teaching-tasks/precheck?semesterId=" + f.semesterId), admin);
        assertThat(pre.path("solvable").asBoolean()).isFalse();
        StringBuilder texts = new StringBuilder();
        pre.path("issues").forEach(i -> texts.append(i.path("message").asText()).append('\n'));
        assertThat(texts.toString()).contains("教室容量不足");

        long jobId = createJob(admin, f.semesterId, "教室容量不足");
        mvc.perform(post("/api/v1/scheduling/jobs/" + jobId + "/run")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        JsonNode status = awaitTerminal(admin, jobId);
        assertThat(status.path("status").asText()).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------ 权限与并发

    @Test
    void permissionsAndDuplicateRunAreEnforced() throws Exception {
        SysUser admin = createUser("sch_adm4", Roles.ADMIN);
        SysUser teacher = createUser("sch_tea4", Roles.TEACHER);
        SysUser student = createUser("sch_stu4", Roles.STUDENT);
        SysOrgUnit clazz = createOrg(null, OrgType.CLASS);
        Fixture f = fixture(admin, teacher, clazz, 60, 64);

        // 学生不能创建教学任务
        mvc.perform(post("/api/v1/scheduling/teaching-tasks")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(taskBody(f, "STU-" + suffix(), teacher.getId(), 64))))
                .andExpect(status().isForbidden());

        long jobId = createJob(admin, f.semesterId, "权限测试");
        // 学生不能启动任务
        mvc.perform(post("/api/v1/scheduling/jobs/" + jobId + "/run")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());

        // 第一次启动
        mvc.perform(post("/api/v1/scheduling/jobs/" + jobId + "/run")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        // 重复启动 → 409
        mvc.perform(post("/api/v1/scheduling/jobs/" + jobId + "/run")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());

        JsonNode status = awaitTerminal(admin, jobId);
        assertThat(status.path("status").asText()).isEqualTo("SUCCEEDED");
        // 已完成任务不能再次启动，只能重试
        mvc.perform(post("/api/v1/scheduling/jobs/" + jobId + "/run")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        JsonNode retried = postJson("/api/v1/scheduling/jobs/" + jobId + "/retry", null, admin);
        assertThat(retried.path("status").asText()).isEqualTo("DRAFT");
        assertThat(retried.path("id").asLong()).isNotEqualTo(jobId);

        // 教师没有 schedule:manage，不能建教室
        Map<String, Object> room = new LinkedHashMap<>();
        room.put("code", "T-" + suffix());
        room.put("name", "教师私建教室");
        room.put("campusId", f.campusId);
        room.put("capacity", 50);
        room.put("roomType", "NORMAL");
        mvc.perform(post("/api/v1/scheduling/classrooms")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON).content(json(room)))
                .andExpect(status().isForbidden());

        // 未登录 401
        mvc.perform(get("/api/v1/scheduling/versions")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 局部重排保留锁定

    @Test
    void lockedEntriesSurviveReschedule() throws Exception {
        SysUser admin = createUser("sch_adm5", Roles.ADMIN);
        SysUser teacher = createUser("sch_tea5", Roles.TEACHER);
        SysOrgUnit clazz = createOrg(null, OrgType.CLASS);
        Fixture f = fixture(admin, teacher, clazz, 60, 64);

        long jobId = createJob(admin, f.semesterId, "锁定测试");
        mvc.perform(post("/api/v1/scheduling/jobs/" + jobId + "/run")
                        .header("Authorization", bearer(admin))).andExpect(status().isOk());
        awaitTerminal(admin, jobId);
        long versionId = ok(get("/api/v1/scheduling/jobs/" + jobId), admin).path("resultVersionId").asLong();

        JsonNode entries = ok(get("/api/v1/scheduling/versions/" + versionId + "/entries"), admin);
        long targetId = entries.get(0).path("id").asLong();
        int lockedDay = entries.get(0).path("dayOfWeek").asInt();
        int lockedStart = entries.get(0).path("startPeriod").asInt();
        putJson("/api/v1/scheduling/versions/" + versionId + "/entries/" + targetId + "/lock",
                Map.of("locked", true, "reason", "固定这节"), admin);

        // 全量重排：以该版本为基准，保留锁定课次
        long replanJob = createJob(admin, f.semesterId, "保留锁定的重排", versionId);
        mvc.perform(post("/api/v1/scheduling/jobs/" + replanJob + "/run")
                        .header("Authorization", bearer(admin))).andExpect(status().isOk());
        JsonNode status = awaitTerminal(admin, replanJob);
        assertThat(status.path("status").asText()).isIn("SUCCEEDED", "PARTIAL");
        long newVersion = ok(get("/api/v1/scheduling/jobs/" + replanJob), admin)
                .path("resultVersionId").asLong();
        assertThat(newVersion).isNotEqualTo(versionId);

        JsonNode newEntries = ok(get("/api/v1/scheduling/versions/" + newVersion + "/entries"), admin);
        boolean kept = false;
        for (JsonNode e : newEntries) {
            if (e.path("taskId").asLong() == entries.get(0).path("taskId").asLong()
                    && e.path("dayOfWeek").asInt() == lockedDay
                    && e.path("startPeriod").asInt() == lockedStart) {
                kept = true;
            }
        }
        assertThat(kept).as("锁定课次在重排后保持原时间: 原 " + lockedDay + "/" + lockedStart
                + "，新版本 " + newEntries).isTrue();
    }

    // ------------------------------------------------------------------ 回归：局部重排

    @Test
    void rescheduleCreatesANewVersionThatActuallyRuns() throws Exception {
        SysUser admin = createUser("sch_adm6", Roles.ADMIN);
        SysUser teacher = createUser("sch_tea6", Roles.TEACHER);
        SysOrgUnit clazz = createOrg(null, OrgType.CLASS);
        Fixture f = fixture(admin, teacher, clazz, 60, 64);

        long jobId = createJob(admin, f.semesterId, "重排基线");
        mvc.perform(post("/api/v1/scheduling/jobs/" + jobId + "/run")
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
        awaitTerminal(admin, jobId);
        long baseVersion = ok(get("/api/v1/scheduling/jobs/" + jobId), admin)
                .path("resultVersionId").asLong();

        MvcResult res = mvc.perform(post("/api/v1/scheduling/versions/" + baseVersion + "/reschedule")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn();
        long replanJob = body(res).path("data").path("id").asLong();

        // 修复前：任务记录在未提交事务里被异步线程读取，永远停在 QUEUED
        JsonNode status = awaitTerminal(admin, replanJob);
        assertThat(status.path("status").asText()).isIn("SUCCEEDED", "PARTIAL");
        long newVersion = ok(get("/api/v1/scheduling/jobs/" + replanJob), admin)
                .path("resultVersionId").asLong();
        assertThat(newVersion).isPositive().isNotEqualTo(baseVersion);
        assertThat(ok(get("/api/v1/scheduling/versions/" + newVersion + "/evaluation"), admin)
                .path("hardViolations").asInt()).isZero();
    }

    // ------------------------------------------------------------------ 回归：CSV 与 Excel BOM

    @Test
    void importCsvAcceptsExcelBomAndQuotedHeader() throws Exception {
        SysUser admin = createUser("sch_adm7", Roles.ADMIN);
        SysUser teacher = createUser("sch_tea7", Roles.TEACHER);
        SysOrgUnit clazz = createOrg(null, OrgType.CLASS);
        Fixture f = fixture(admin, teacher, clazz, 60, 64);

        String courseCode = ok(get("/api/v1/scheduling/courses/" + f.courseId), admin).path("code").asText();
        String tcCode = ok(get("/api/v1/scheduling/teaching-classes/" + f.teachingClassId), admin)
                .path("code").asText();
        String newCode = "CSV-" + suffix();
        String csv = "\uFEFFcode,courseCode,teachingClassCode,teacherUsernames,startWeek,endWeek,weekParity,"
                + "sessionsPerWeek,periodsPerSession,totalHours,roomRequired,note\n"
                + newCode + "," + courseCode + "," + tcCode + "," + teacher.getUsername()
                + ",1,16,ALL,2,2,64,true,\"导入\"\n";

        JsonNode result = body(mvc.perform(post("/api/v1/scheduling/teaching-tasks/import")
                        .queryParam("semesterId", String.valueOf(f.semesterId))
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.valueOf("text/csv"))
                        .content(csv))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(result.path("failed").asInt()).isZero();
        assertThat(result.path("created").asInt()).isEqualTo(1);

        JsonNode list = ok(get("/api/v1/scheduling/teaching-tasks?semesterId=" + f.semesterId
                + "&keyword=" + newCode), admin);
        assertThat(list.path("total").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("note").asText()).isEqualTo("导入");
    }

    // ------------------------------------------------------------------ 回归：停用任务不影响既有版本复核

    @Test
    void disabledTaskStillCountsInVersionEvaluation() throws Exception {
        SysUser admin = createUser("sch_adm8", Roles.ADMIN);
        SysUser teacher = createUser("sch_tea8", Roles.TEACHER);
        SysOrgUnit clazz = createOrg(null, OrgType.CLASS);
        Fixture f = fixture(admin, teacher, clazz, 60, 64);

        long jobId = createJob(admin, f.semesterId, "停用任务前");
        mvc.perform(post("/api/v1/scheduling/jobs/" + jobId + "/run")
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
        awaitTerminal(admin, jobId);
        long versionId = ok(get("/api/v1/scheduling/jobs/" + jobId), admin)
                .path("resultVersionId").asLong();

        JsonNode task = ok(get("/api/v1/scheduling/teaching-tasks/" + f.taskId), admin);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("semesterId", f.semesterId);
        update.put("code", task.path("code").asText());
        update.put("courseId", f.courseId);
        update.put("teachingClassId", f.teachingClassId);
        update.put("startWeek", 1);
        update.put("endWeek", 16);
        update.put("weekParity", "ALL");
        update.put("sessionsPerWeek", 2);
        update.put("periodsPerSession", 2);
        update.put("totalHours", 64);
        update.put("teacherIds", List.of(teacher.getId()));
        update.put("status", "DISABLED");
        update.put("version", task.path("version").asInt());
        putJson("/api/v1/scheduling/teaching-tasks/" + f.taskId, update, admin);

        // 停用后既有版本仍应完整参与复核：2 次课、0 冲突，而不是“数据不完整”
        JsonNode evaluation = ok(get("/api/v1/scheduling/versions/" + versionId + "/evaluation"), admin);
        assertThat(evaluation.path("placedSessions").asInt()).isEqualTo(2);
        assertThat(evaluation.path("hardViolations").asInt()).isZero();
        for (JsonNode c : evaluation.path("conflicts")) {
            assertThat(c.path("code").asText()).isNotEqualTo("DATA_INVALID");
        }
    }

    // ------------------------------------------------------------------ 回归：已发布课表保护与复核权限

    @Test
    void publishedTimetableBlocksCalendarChangesAndCheckNeedsAdjustPermission() throws Exception {
        SysUser admin = createUser("sch_adm9", Roles.ADMIN);
        SysUser teacher = createUser("sch_tea9", Roles.TEACHER);
        SysOrgUnit clazz = createOrg(null, OrgType.CLASS);
        Fixture f = fixture(admin, teacher, clazz, 60, 64);

        long jobId = createJob(admin, f.semesterId, "发布保护");
        mvc.perform(post("/api/v1/scheduling/jobs/" + jobId + "/run")
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
        awaitTerminal(admin, jobId);
        long versionId = ok(get("/api/v1/scheduling/jobs/" + jobId), admin)
                .path("resultVersionId").asLong();
        postJson("/api/v1/scheduling/versions/" + versionId + "/publish", Map.of("reason", "发布"), admin);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("teachingWeeks", 18);
        config.put("firstWeekStart", LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toString());
        config.put("teachingDays", List.of(1, 2, 3, 4, 5));
        config.put("periodsPerDay", 8);
        mvc.perform(put("/api/v1/scheduling/terms/" + f.semesterId + "/config")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(config)))
                .andExpect(status().isConflict());

        // 撤销发布后可以改
        postJson("/api/v1/scheduling/versions/" + versionId + "/unpublish", Map.of("reason", "撤回"), admin);
        putJson("/api/v1/scheduling/terms/" + f.semesterId + "/config", config, admin);

        // 只有 schedule:read 的角色不能触发复核（复核会写库）
        jdbc.update("INSERT IGNORE INTO sys_role (code, name, description, built_in) "
                + "VALUES ('SCHED_VIEWER', '排课只读', 'integration test', FALSE)");
        jdbc.update("INSERT IGNORE INTO sys_role_permission (role_id, permission_id) "
                + "SELECT r.id, p.id FROM sys_role r JOIN sys_permission p ON p.code = 'schedule:read' "
                + "WHERE r.code = 'SCHED_VIEWER'");
        SysUser viewer = createUser("sch_view", "SCHED_VIEWER");
        mvc.perform(get("/api/v1/scheduling/versions/" + versionId + "/evaluation")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/scheduling/versions/" + versionId + "/check")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 测试夹具

    private record Fixture(long semesterId, long campusId, long courseId, long teachingClassId,
                           long taskId) {
    }

    private Fixture fixture(SysUser admin, SysUser teacher, SysOrgUnit clazz, int roomCapacity,
                            int totalHours) throws Exception {
        return fixture(admin, teacher, clazz, roomCapacity, totalHours, 40);
    }

    private Fixture fixture(SysUser admin, SysUser teacher, SysOrgUnit clazz, int roomCapacity,
                            int totalHours, int students) throws Exception {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("code", "T-" + suffix());
        term.put("name", "排课测试学期 " + suffix());
        term.put("startDate", monday.toString());
        term.put("endDate", monday.plusWeeks(17).toString());
        term.put("teachingWeeks", 16);
        term.put("firstWeekStart", monday.toString());
        term.put("teachingDays", List.of(1, 2, 3, 4, 5));
        term.put("periodsPerDay", 8);
        long semesterId = ok(post("/api/v1/scheduling/terms").content(json(term)), admin).path("id").asLong();

        Map<String, Object> campus = new LinkedHashMap<>();
        campus.put("code", "C-" + suffix());
        campus.put("name", "测试校区");
        long campusId = ok(post("/api/v1/scheduling/campuses").content(json(campus)), admin).path("id").asLong();

        Map<String, Object> room = new LinkedHashMap<>();
        room.put("code", "R-" + suffix());
        room.put("name", "测试教室");
        room.put("campusId", campusId);
        room.put("capacity", roomCapacity);
        room.put("roomType", "NORMAL");
        ok(post("/api/v1/scheduling/classrooms").content(json(room)), admin);

        putJson("/api/v1/scheduling/teachers/" + teacher.getId(), Map.of("maxWeeklyPeriods", 20), admin);
        putJson("/api/v1/scheduling/classes/" + clazz.getId(),
                Map.of("studentCount", students, "grade", "2026"), admin);

        Map<String, Object> course = new LinkedHashMap<>();
        course.put("code", "CS-" + suffix());
        course.put("name", "Java 程序设计");
        course.put("courseType", "THEORY");
        course.put("credit", 4.0);
        course.put("totalHours", totalHours);
        course.put("priority", 8);
        long courseId = ok(post("/api/v1/scheduling/courses").content(json(course)), admin).path("id").asLong();

        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("semesterId", semesterId);
        tc.put("code", "TC-" + suffix());
        tc.put("name", "Java 教学班");
        tc.put("members", List.of(Map.of("classId", clazz.getId(), "fullClass", true)));
        long tcId = ok(post("/api/v1/scheduling/teaching-classes").content(json(tc)), admin).path("id").asLong();

        String taskJson = json(taskBody(new Fixture(semesterId, campusId, courseId, tcId, 0),
                "T-" + suffix(), teacher.getId(), totalHours));
        long taskId = ok(post("/api/v1/scheduling/teaching-tasks").content(taskJson), admin)
                .path("id").asLong();
        return new Fixture(semesterId, campusId, courseId, tcId, taskId);
    }

    private Map<String, Object> taskBody(Fixture f, String code, long teacherId, int totalHours) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("semesterId", f.semesterId);
        body.put("code", code);
        body.put("courseId", f.courseId);
        body.put("teachingClassId", f.teachingClassId);
        body.put("startWeek", 1);
        body.put("endWeek", 16);
        body.put("weekParity", "ALL");
        body.put("sessionsPerWeek", 2);
        body.put("periodsPerSession", 2);
        body.put("totalHours", totalHours);
        body.put("teacherIds", List.of(teacherId));
        return body;
    }

    private long createJob(SysUser admin, long semesterId, String name) throws Exception {
        return createJob(admin, semesterId, name, null);
    }

    private long createJob(SysUser admin, long semesterId, String name, Long baseVersionId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("semesterId", semesterId);
        body.put("name", name);
        body.put("mode", baseVersionId == null ? "FULL" : "INCREMENTAL");
        body.put("baseVersionId", baseVersionId);
        body.put("timeLimitSeconds", 30);
        body.put("workers", 2);
        body.put("randomSeed", 3);
        body.put("allowPartial", true);
        return ok(post("/api/v1/scheduling/jobs").content(json(body)), admin).path("id").asLong();
    }

    private JsonNode awaitTerminal(SysUser admin, long jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            JsonNode status = ok(get("/api/v1/scheduling/jobs/" + jobId + "/status"), admin);
            String value = status.path("status").asText();
            if (List.of("SUCCEEDED", "PARTIAL", "INFEASIBLE", "UNKNOWN", "FAILED", "CANCELLED")
                    .contains(value)) {
                return status;
            }
        }
        throw new AssertionError("排课任务在 90 秒内未结束：" + jobId);
    }

    private JsonNode ok(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                        SysUser user) throws Exception {
        builder.header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON);
        MvcResult result = mvc.perform(builder).andReturn();
        int code = result.getResponse().getStatus();
        if (code != 200) {
            throw new AssertionError("期望 200，实际 " + code + "，响应："
                    + result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        }
        return body(result).path("data");
    }

    private JsonNode postJson(String url, Object body, SysUser user) throws Exception {
        var builder = post(url).header("Authorization", bearer(user));
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        return body(mvc.perform(builder).andExpect(status().isOk()).andReturn()).path("data");
    }

    private JsonNode putJson(String url, Object body, SysUser user) throws Exception {
        return body(mvc.perform(put(url).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk()).andReturn()).path("data");
    }
}
