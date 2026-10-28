package com.campus.platform.support;

import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段六集成测试的公共操作：建学期、建课、排课、开放、选课、请假。
 * <p>
 * 所有编码都带随机后缀；学期日期以“今天”为中心，选课窗口默认前后各一天，保证测试不依赖固定日期。
 */
public abstract class CourseTestSupport extends IntegrationTestBase {

    protected static LocalDate today() {
        return LocalDate.now();
    }

    /** 默认选课窗口：昨天 00:00 ~ 明天 23:59。 */
    protected long createOpenSemester(SysUser admin) throws Exception {
        return createSemester(admin, "SEM-" + suffix(),
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));
    }

    protected long createSemester(SysUser admin, String code, LocalDateTime windowStart,
                                  LocalDateTime windowEnd) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "学期" + code);
        body.put("startDate", today().minusDays(30).toString());
        body.put("endDate", today().plusDays(120).toString());
        body.put("selectionStartAt", windowStart.toString());
        body.put("selectionEndAt", windowEnd.toString());
        MvcResult result = mvc.perform(post("/api/v1/semesters")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    /** 学期置为 ACTIVE（选课窗口默认已打开）。 */
    protected void activateSemester(SysUser admin, long semesterId) throws Exception {
        mvc.perform(patch("/api/v1/semesters/" + semesterId + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "ACTIVE"))))
                .andExpect(status().isOk());
    }

    /** 建一个 ACTIVE 且选课窗口打开的学期。 */
    protected long createActiveSemester(SysUser admin) throws Exception {
        long id = createOpenSemester(admin);
        activateSemester(admin, id);
        return id;
    }

    protected long createCourse(SysUser teacher, long semesterId, String code, int capacity) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("semesterId", semesterId);
        body.put("code", code);
        body.put("name", "课程" + code);
        body.put("credit", 3.0);
        body.put("description", "集成测试课程");
        body.put("capacity", capacity);
        MvcResult result = mvc.perform(post("/api/v1/courses")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    protected long addSchedule(SysUser teacher, long courseId, int dayOfWeek, int startPeriod, int endPeriod)
            throws Exception {
        return addSchedule(teacher, courseId, dayOfWeek, startPeriod, endPeriod, 1, 16, "ALL");
    }

    protected long addSchedule(SysUser teacher, long courseId, int dayOfWeek, int startPeriod, int endPeriod,
                               int startWeek, int endWeek, String parity) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("dayOfWeek", dayOfWeek);
        body.put("startPeriod", startPeriod);
        body.put("endPeriod", endPeriod);
        body.put("startWeek", startWeek);
        body.put("endWeek", endWeek);
        body.put("weekParity", parity);
        body.put("classroom", "A" + dayOfWeek + "0" + startPeriod);
        MvcResult result = mvc.perform(post("/api/v1/courses/" + courseId + "/schedules")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode schedules = body(result).get("data");
        long maxId = 0;
        for (JsonNode node : schedules) {
            maxId = Math.max(maxId, node.get("id").asLong());
        }
        return maxId;
    }

    protected void changeCourseStatus(SysUser actor, long courseId, String status) throws Exception {
        mvc.perform(patch("/api/v1/courses/" + courseId + "/status")
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", status))))
                .andExpect(status().isOk());
    }

    /** 建课 + 排课 + 开放，返回课程 ID。 */
    protected long createOpenCourse(SysUser teacher, long semesterId, String code, int capacity,
                                    int dayOfWeek, int startPeriod, int endPeriod) throws Exception {
        long courseId = createCourse(teacher, semesterId, code, capacity);
        addSchedule(teacher, courseId, dayOfWeek, startPeriod, endPeriod);
        changeCourseStatus(teacher, courseId, "OPEN");
        return courseId;
    }

    protected long selectCourse(SysUser student, long courseId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseId))))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    protected JsonNode courseDetail(SysUser viewer, long courseId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/courses/" + courseId)
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data");
    }

    protected JsonNode availableCourses(SysUser student, long semesterId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/courses/available")
                        .param("semesterId", Long.toString(semesterId))
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data");
    }

    protected JsonNode mySelections(SysUser student) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/course-selections/me")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data");
    }

    protected long submitLeave(SysUser student, long courseId, LocalDateTime start, LocalDateTime end,
                               String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("courseId", courseId);
        body.put("startAt", start.toString());
        body.put("endAt", end.toString());
        body.put("reason", reason);
        MvcResult result = mvc.perform(post("/api/v1/leave-requests")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    protected JsonNode leaveDetail(SysUser viewer, long leaveId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/leave-requests/" + leaveId)
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data");
    }

    /** 找出 available 列表中指定课程的条目。 */
    protected static JsonNode findCourse(JsonNode items, long courseId) {
        for (JsonNode item : items) {
            if (item.get("courseId").asLong() == courseId) {
                return item;
            }
        }
        return null;
    }
}
