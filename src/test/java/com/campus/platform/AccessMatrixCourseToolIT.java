package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.CourseTestSupport;
import com.campus.platform.user.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段八：课程与 AI 工具接口的角色访问矩阵回归。
 * <p>
 * 与阶段二的 {@link AccessMatrixIT} 互补，覆盖阶段六、七新增的写接口，
 * 并确认“管理员/教师的正向路径确实可用”（避免只测 403 的假阳性）。
 */
class AccessMatrixCourseToolIT extends CourseTestSupport {

    @Test
    void courseAndToolEndpointsFollowRoleMatrix() throws Exception {
        SysUser admin = createUser("mx8_adm", Roles.ADMIN);
        SysUser teacher = createUser("mx8_tea", Roles.TEACHER);
        SysUser student = createUser("mx8_stu", Roles.STUDENT);
        SysUser noRole = createUser("mx8_none");

        // 学期读：有 course:read 的角色可读，无角色用户 403，未登录 401
        for (SysUser user : new SysUser[]{admin, teacher, student}) {
            mvc.perform(get("/api/v1/semesters").header("Authorization", bearer(user)))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/semesters").header("Authorization", bearer(noRole)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/semesters")).andExpect(status().isUnauthorized());

        // 学期写：仅管理员
        mvc.perform(post("/api/v1/semesters")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(semesterBody())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/semesters")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(semesterBody())))
                .andExpect(status().isForbidden());

        long semesterId = createActiveSemester(admin);

        // 建课：教师可以，学生/无角色用户 403
        mvc.perform(post("/api/v1/courses")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(courseBody(semesterId, "MX8-" + suffix()))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/courses")
                        .header("Authorization", bearer(noRole))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(courseBody(semesterId, "MX8-" + suffix()))))
                .andExpect(status().isForbidden());
        long courseId = createCourse(teacher, semesterId, "MX8-" + suffix(), 10);

        // 排课：学生 403，主讲教师 200
        mvc.perform(post("/api/v1/courses/" + courseId + "/schedules")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody())))
                .andExpect(status().isForbidden());
        addSchedule(teacher, courseId, 1, 1, 2);
        changeCourseStatus(teacher, courseId, "OPEN");

        // 名单：学生 403，教师 200
        mvc.perform(get("/api/v1/courses/" + courseId + "/selections")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/courses/" + courseId + "/selections")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk());

        // 选课：教师没有 selection:select → 403；学生可以
        mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseId))))
                .andExpect(status().isForbidden());
        selectCourse(student, courseId);

        // AI 工具：无 ai:tool:use 403；学生可读目录但调不了建课工具
        mvc.perform(get("/api/v1/ai/tools").header("Authorization", bearer(noRole)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ai/tools").header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/ai/tool-calls")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tool", "createCourse", "arguments",
                                Map.of("semesterId", semesterId, "code", "X-" + suffix(),
                                        "name", "越权建课", "credit", 3, "capacity", 10)))))
                .andExpect(status().isForbidden());

        // 调用记录：本人可读，无角色用户 403
        mvc.perform(get("/api/v1/ai/tool-calls").header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/ai/tool-calls").header("Authorization", bearer(noRole)))
                .andExpect(status().isForbidden());
    }

    private static Map<String, Object> semesterBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("code", "MX8-" + suffix());
        body.put("name", "矩阵学期" + suffix());
        body.put("startDate", LocalDate.now().minusDays(10).toString());
        body.put("endDate", LocalDate.now().plusDays(100).toString());
        body.put("selectionStartAt", LocalDateTime.now().minusDays(1).toString());
        body.put("selectionEndAt", LocalDateTime.now().plusDays(1).toString());
        return body;
    }

    private static Map<String, Object> courseBody(long semesterId, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("semesterId", semesterId);
        body.put("code", code);
        body.put("name", "矩阵课程" + code);
        body.put("credit", 3.0);
        body.put("capacity", 10);
        return body;
    }

    private static Map<String, Object> scheduleBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("dayOfWeek", 1);
        body.put("startPeriod", 1);
        body.put("endPeriod", 2);
        body.put("startWeek", 1);
        body.put("endWeek", 16);
        body.put("weekParity", "ALL");
        body.put("classroom", "MX101");
        return body;
    }
}
