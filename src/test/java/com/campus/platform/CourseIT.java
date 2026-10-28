package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.CourseTestSupport;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

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

/** 阶段六：课程 CRUD、状态机、可选课程与停开级联。 */
class CourseIT extends CourseTestSupport {

    @Test
    void teacherCreatesCourseAsPrimaryAndOnlyPrimaryCanEdit() throws Exception {
        SysUser admin = createUser("course_adm", Roles.ADMIN);
        SysUser teacher = createUser("course_tea", Roles.TEACHER);
        SysUser other = createUser("course_other", Roles.TEACHER);
        long semesterId = createActiveSemester(admin);
        String code = "CS-" + suffix();

        long courseId = createCourse(teacher, semesterId, code, 30);
        JsonNode detail = courseDetail(teacher, courseId);
        assertThat(detail.get("status").asText()).isEqualTo("DRAFT");
        assertThat(detail.at("/teachers").size()).isEqualTo(1);
        assertThat(detail.at("/teachers/0/teacherId").asLong()).isEqualTo(teacher.getId());
        assertThat(detail.at("/teachers/0/teacherRole").asText()).isEqualTo("PRIMARY");
        assertThat(detail.at("/schedules").size()).isZero();
        assertThat(detail.get("selectable").asBoolean()).isFalse();

        // 其他教师不能修改
        Map<String, Object> update = updateBody("他人修改", 3.0, 30, 0);
        mvc.perform(put("/api/v1/courses/" + courseId)
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isForbidden());

        // 主讲可以修改；过期 version 冲突
        mvc.perform(put("/api/v1/courses/" + courseId)
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updateBody("主讲修改", 2.5, 25, 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("主讲修改"))
                .andExpect(jsonPath("$.data.credit").value(2.5))
                .andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(put("/api/v1/courses/" + courseId)
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updateBody("重复提交", 2.5, 25, 0))))
                .andExpect(status().isConflict());

        // 管理员可以修改任意课程
        mvc.perform(put("/api/v1/courses/" + courseId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updateBody("管理员修改", 3.0, 40, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity").value(40))
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void courseStatusMachineAndCancelCascade() throws Exception {
        SysUser admin = createUser("course_st_adm", Roles.ADMIN);
        SysUser teacher = createUser("course_st_tea", Roles.TEACHER);
        SysUser student = createUser("course_st_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createCourse(teacher, semesterId, "ST-" + suffix(), 10);

        // 未排课不能开放
        mvc.perform(patch("/api/v1/courses/" + courseId + "/status")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "OPEN"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COURSE_STATUS_INVALID"));

        addSchedule(teacher, courseId, 1, 1, 2);
        changeCourseStatus(teacher, courseId, "OPEN");
        assertThat(courseDetail(teacher, courseId).get("status").asText()).isEqualTo("OPEN");

        // 学生选课占用一个名额
        selectCourse(student, courseId);
        assertThat(courseDetail(teacher, courseId).get("selectedCount").asInt()).isEqualTo(1);

        // 关闭后不再接受选课
        changeCourseStatus(teacher, courseId, "CLOSED");
        mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(createUser("course_st_stu2", Roles.STUDENT)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseId))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SELECTION_COURSE_NOT_OPEN"));

        // 停开：退掉全部选课、计数清零
        changeCourseStatus(teacher, courseId, "CANCELLED");
        JsonNode cancelled = courseDetail(teacher, courseId);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("selectedCount").asInt()).isZero();
        assertThat(mySelections(student).size()).isZero();

        // 停开对普通学生表现为 404
        mvc.perform(get("/api/v1/courses/" + courseId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/courses/" + courseId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        // 终态：不能回到 OPEN，也不能改回 DRAFT
        mvc.perform(patch("/api/v1/courses/" + courseId + "/status")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "OPEN"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COURSE_STATUS_INVALID"));
        mvc.perform(patch("/api/v1/courses/" + courseId + "/status")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "DRAFT"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COURSE_STATUS_INVALID"));
    }

    @Test
    void capacityCannotBeReducedBelowSelectedCount() throws Exception {
        SysUser admin = createUser("course_cap_adm", Roles.ADMIN);
        SysUser teacher = createUser("course_cap_tea", Roles.TEACHER);
        SysUser a = createUser("course_cap_a", Roles.STUDENT);
        SysUser b = createUser("course_cap_b", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "CAP-" + suffix(), 5, 2, 1, 2);
        selectCourse(a, courseId);
        selectCourse(b, courseId);

        mvc.perform(put("/api/v1/courses/" + courseId)
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updateBody("缩容", 3.0, 1, 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COURSE_CAPACITY_INVALID"));

        // 缩到等于已选人数是允许的
        mvc.perform(put("/api/v1/courses/" + courseId)
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updateBody("缩容到已选人数", 3.0, 2, 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity").value(2));
    }

    @Test
    void availableCoursesExposeBlockedReasons() throws Exception {
        SysUser admin = createUser("course_av_adm", Roles.ADMIN);
        SysUser teacher = createUser("course_av_tea", Roles.TEACHER);
        SysUser teacher2 = createUser("course_av_tea2", Roles.TEACHER);
        SysUser student = createUser("course_av_stu", Roles.STUDENT);
        SysUser other = createUser("course_av_other", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);

        long selected = createOpenCourse(teacher, semesterId, "AV1-" + suffix(), 10, 1, 1, 2);
        // 另一位教师承担，避免排课时触发“教师课表冲突”
        long conflicting = createOpenCourse(teacher2, semesterId, "AV2-" + suffix(), 10, 1, 2, 3);
        long free = createOpenCourse(teacher, semesterId, "AV3-" + suffix(), 10, 3, 1, 2);
        long full = createOpenCourse(teacher, semesterId, "AV4-" + suffix(), 1, 5, 5, 6);

        selectCourse(student, selected);
        selectCourse(other, full);

        JsonNode items = availableCourses(student, semesterId);
        assertThat(findCourse(items, selected).get("blockedReason").asText()).isEqualTo("ALREADY_SELECTED");

        JsonNode conflict = findCourse(items, conflicting);
        assertThat(conflict.get("blockedReason").asText()).isEqualTo("TIME_CONFLICT");
        assertThat(conflict.get("selectable").asBoolean()).isFalse();
        assertThat(conflict.get("conflictCourseName").asText()).contains("AV1-");

        JsonNode fullItem = findCourse(items, full);
        assertThat(fullItem.get("blockedReason").asText()).isEqualTo("FULL");
        assertThat(fullItem.get("selectedCount").asInt()).isEqualTo(1);

        JsonNode freeItem = findCourse(items, free);
        assertThat(freeItem.get("selectable").asBoolean()).isTrue();
        assertThat(freeItem.get("blockedReason").isNull()).isTrue();
        assertThat(freeItem.get("schedules").size()).isEqualTo(1);

        // 选课窗口关闭：所有课程都不可选
        long closedWindowSemester = createSemester(admin, "SEM-" + suffix(),
                LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(5));
        activateSemester(admin, closedWindowSemester);
        long closedWindowCourse = createOpenCourse(teacher, closedWindowSemester, "AV5-" + suffix(), 10, 4, 1, 2);
        JsonNode closedItems = availableCourses(student, closedWindowSemester);
        assertThat(findCourse(closedItems, closedWindowCourse).get("blockedReason").asText())
                .isEqualTo("WINDOW_CLOSED");
        mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", closedWindowCourse))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SELECTION_WINDOW_CLOSED"));
    }

    @Test
    void cancelledCourseIsExcludedFromListAndStatusFilterIsAdminOnly() throws Exception {
        SysUser admin = createUser("course_cx_adm", Roles.ADMIN);
        SysUser teacher = createUser("course_cx_tea", Roles.TEACHER);
        SysUser student = createUser("course_cx_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "CX-" + suffix(), 10, 2, 3, 4);
        changeCourseStatus(teacher, courseId, "CANCELLED");

        MvcResult list = mvc.perform(get("/api/v1/courses")
                        .param("semesterId", Long.toString(semesterId))
                        .param("keyword", "CX-")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(list).at("/data/total").asLong()).isZero();

        mvc.perform(get("/api/v1/courses")
                        .param("status", "CANCELLED")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
        MvcResult adminList = mvc.perform(get("/api/v1/courses")
                        .param("semesterId", Long.toString(semesterId))
                        .param("status", "CANCELLED")
                        .param("keyword", "CX-")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(adminList).at("/data/total").asLong()).isEqualTo(1);
    }

    @Test
    void courseCreationRulesAndDefaultOrg() throws Exception {
        SysUser admin = createUser("course_cr_adm", Roles.ADMIN);
        SysOrgUnit org = createOrg(null, OrgType.COLLEGE);
        SysUser teacher = createUserInOrg("course_cr_tea", org.getId(), Roles.TEACHER);
        SysUser student = createUser("course_cr_stu", Roles.STUDENT);
        SysUser noRole = createUser("course_cr_none");
        long semesterId = createActiveSemester(admin);

        // 学生/无角色用户不能建课
        mvc.perform(post("/api/v1/courses")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(courseBody(semesterId, "CR-" + suffix(), 10))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/courses")
                        .header("Authorization", bearer(noRole))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(courseBody(semesterId, "CR-" + suffix(), 10))))
                .andExpect(status().isForbidden());

        // 默认开课组织取创建人所属组织
        String code = "CR-" + suffix();
        long courseId = createCourse(teacher, semesterId, code, 10);
        assertThat(courseDetail(teacher, courseId).get("orgUnitId").asLong()).isEqualTo(org.getId());

        // 同学期课程编码唯一
        mvc.perform(post("/api/v1/courses")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(courseBody(semesterId, code, 10))))
                .andExpect(status().isConflict());

        // 学期不存在
        mvc.perform(post("/api/v1/courses")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(courseBody(99999999L, "CR-" + suffix(), 10))))
                .andExpect(status().isNotFound());

        // 已结束学期不能建课
        long closedSemester = createOpenSemester(admin);
        mvc.perform(patch("/api/v1/semesters/" + closedSemester + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "CLOSED"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/courses")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(courseBody(closedSemester, "CR-" + suffix(), 10))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEMESTER_CLOSED"));
    }

    private static Map<String, Object> updateBody(String name, double credit, int capacity, int version) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("credit", credit);
        body.put("description", "更新后的简介");
        body.put("capacity", capacity);
        body.put("version", version);
        return body;
    }

    private static Map<String, Object> courseBody(long semesterId, String code, int capacity) {
        Map<String, Object> body = new HashMap<>();
        body.put("semesterId", semesterId);
        body.put("code", code);
        body.put("name", "课程" + code);
        body.put("credit", 3.0);
        body.put("capacity", capacity);
        return body;
    }
}
