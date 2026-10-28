package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.CourseTestSupport;
import com.campus.platform.user.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 阶段六：学期管理、选课窗口与状态机。 */
class SemesterIT extends CourseTestSupport {

    @Test
    void adminCreatesAndUpdatesSemesterWithValidation() throws Exception {
        SysUser admin = createUser("sem_adm", Roles.ADMIN);
        SysUser student = createUser("sem_stu", Roles.STUDENT);
        String code = "SEM-" + suffix();

        long id = createSemester(admin, code, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));
        MvcResult detail = mvc.perform(get("/api/v1/semesters/" + id)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(code))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.courseCount").value(0))
                .andReturn();
        // DRAFT 学期即使时间在窗口内也不允许选课
        assertThat(body(detail).at("/data/selectionOpen").asBoolean()).isFalse();

        // 编码重复
        mvc.perform(post("/api/v1/semesters")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(semesterBody(code))))
                .andExpect(status().isConflict());

        // 日期区间颠倒
        Map<String, Object> bad = semesterBody("SEM-" + suffix());
        bad.put("startDate", LocalDate.now().plusDays(10).toString());
        bad.put("endDate", LocalDate.now().toString());
        mvc.perform(post("/api/v1/semesters")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bad)))
                .andExpect(status().isBadRequest());

        // 选课窗口颠倒
        Map<String, Object> badWindow = semesterBody("SEM-" + suffix());
        badWindow.put("selectionStartAt", LocalDateTime.now().plusDays(2).toString());
        badWindow.put("selectionEndAt", LocalDateTime.now().toString());
        mvc.perform(post("/api/v1/semesters")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(badWindow)))
                .andExpect(status().isBadRequest());

        // 修改
        Map<String, Object> update = semesterBody(code);
        update.put("name", "修改后的学期");
        mvc.perform(put("/api/v1/semesters/" + id)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("修改后的学期"));
    }

    @Test
    void semesterStatusMachineIsOneWay() throws Exception {
        SysUser admin = createUser("sem_status_adm", Roles.ADMIN);
        long id = createOpenSemester(admin);

        // DRAFT → ACTIVE：窗口打开
        activateSemester(admin, id);
        mvc.perform(get("/api/v1/semesters/" + id)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.selectionOpen").value(true));

        // ACTIVE 不能回退为 DRAFT
        mvc.perform(patch("/api/v1/semesters/" + id + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "DRAFT"))))
                .andExpect(status().isUnprocessableEntity());

        // ACTIVE → CLOSED
        mvc.perform(patch("/api/v1/semesters/" + id + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "CLOSED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.selectionOpen").value(false));

        // CLOSED 为终态：不能再打开，也不能修改
        mvc.perform(patch("/api/v1/semesters/" + id + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "ACTIVE"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEMESTER_CLOSED"));

        Map<String, Object> update = semesterBody("SEM-" + suffix());
        mvc.perform(put("/api/v1/semesters/" + id)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEMESTER_CLOSED"));
    }

    @Test
    void currentSemesterPrefersActiveSemesterCoveringToday() throws Exception {
        SysUser admin = createUser("sem_cur_adm", Roles.ADMIN);
        String code = "SEM-" + suffix();
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "当前学期" + code);
        // 开始日期取今天，使其在“按开始日期倒序”中排在其它测试学期之前
        body.put("startDate", LocalDate.now().toString());
        body.put("endDate", LocalDate.now().plusDays(120).toString());
        body.put("selectionStartAt", LocalDateTime.now().minusDays(1).toString());
        body.put("selectionEndAt", LocalDateTime.now().plusDays(1).toString());
        MvcResult created = mvc.perform(post("/api/v1/semesters")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        long id = body(created).at("/data/id").asLong();
        activateSemester(admin, id);

        mvc.perform(get("/api/v1/semesters/current")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void semesterWritesRequireManageAll() throws Exception {
        SysUser admin = createUser("sem_perm_adm", Roles.ADMIN);
        SysUser teacher = createUser("sem_perm_tea", Roles.TEACHER);
        SysUser student = createUser("sem_perm_stu", Roles.STUDENT);
        SysUser noRole = createUser("sem_perm_none");
        long id = createOpenSemester(admin);

        // 教师/学生不能建学期
        mvc.perform(post("/api/v1/semesters")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(semesterBody("SEM-" + suffix()))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/semesters")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(semesterBody("SEM-" + suffix()))))
                .andExpect(status().isForbidden());

        // 读：教师/学生可以，无角色用户没有 course:read
        mvc.perform(get("/api/v1/semesters").header("Authorization", bearer(teacher)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/semesters").header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/semesters").header("Authorization", bearer(noRole)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/semesters"))
                .andExpect(status().isUnauthorized());

        // 教师不能改状态
        mvc.perform(patch("/api/v1/semesters/" + id + "/status")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "ACTIVE"))))
                .andExpect(status().isForbidden());

        // 不存在的学期
        mvc.perform(get("/api/v1/semesters/99999999")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    private static Map<String, Object> semesterBody(String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "学期" + code);
        body.put("startDate", LocalDate.now().minusDays(30).toString());
        body.put("endDate", LocalDate.now().plusDays(120).toString());
        body.put("selectionStartAt", LocalDateTime.now().minusDays(1).toString());
        body.put("selectionEndAt", LocalDateTime.now().plusDays(1).toString());
        return body;
    }
}
