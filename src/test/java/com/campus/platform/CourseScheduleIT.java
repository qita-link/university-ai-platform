package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.CourseTestSupport;
import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 阶段六：排课校验、时间冲突、单双周与我的课表。 */
class CourseScheduleIT extends CourseTestSupport {

    @Test
    void scheduleValidatesInputAndDetectsSameCourseOverlap() throws Exception {
        SysUser admin = createUser("sched_adm", Roles.ADMIN);
        SysUser teacher = createUser("sched_tea", Roles.TEACHER);
        long semesterId = createActiveSemester(admin);
        long courseId = createCourse(teacher, semesterId, "SC-" + suffix(), 10);

        long first = addSchedule(teacher, courseId, 1, 1, 2);
        // 与本课程已有排课重叠
        mvc.perform(post("/api/v1/courses/" + courseId + "/schedules")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody(1, 2, 3, 1, 16, "ALL"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COURSE_SCHEDULE_CONFLICT"));
        // 不重叠可以新增
        long third = addSchedule(teacher, courseId, 1, 3, 4);

        // 节次区间颠倒
        mvc.perform(post("/api/v1/courses/" + courseId + "/schedules")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody(2, 4, 3, 1, 16, "ALL"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COURSE_SCHEDULE_INVALID"));
        // 周次区间颠倒
        mvc.perform(post("/api/v1/courses/" + courseId + "/schedules")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody(2, 1, 2, 10, 5, "ALL"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COURSE_SCHEDULE_INVALID"));
        // Bean Validation：节次越界
        mvc.perform(post("/api/v1/courses/" + courseId + "/schedules")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody(2, 0, 2, 1, 16, "ALL"))))
                .andExpect(status().isBadRequest());

        // 修改：与另一条排课冲突则拒绝，改到空档则成功
        mvc.perform(put("/api/v1/courses/" + courseId + "/schedules/" + third)
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody(1, 2, 3, 1, 16, "ALL"))))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/courses/" + courseId + "/schedules/" + third)
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody(4, 3, 4, 1, 16, "ALL"))))
                .andExpect(status().isOk());
        MvcResult listed = mvc.perform(get("/api/v1/courses/" + courseId + "/schedules")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode schedules = body(listed).get("data");
        assertThat(schedules.size()).isEqualTo(2);

        // 删除
        mvc.perform(delete("/api/v1/courses/" + courseId + "/schedules/" + first)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk());
        assertThat(courseDetail(teacher, courseId).at("/schedules").size()).isEqualTo(1);

        // 排课不属于该课程 → 404
        mvc.perform(delete("/api/v1/courses/" + courseId + "/schedules/" + first)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isNotFound());
    }

    @Test
    void oddAndEvenParityCanShareTheSameSlot() throws Exception {
        SysUser admin = createUser("sched_par_adm", Roles.ADMIN);
        SysUser teacher = createUser("sched_par_tea", Roles.TEACHER);
        long semesterId = createActiveSemester(admin);
        long courseId = createCourse(teacher, semesterId, "PAR-" + suffix(), 10);

        long odd = addSchedule(teacher, courseId, 2, 1, 2, 1, 16, "ODD");
        // 单周与双周互斥，同一时间可以排两条
        long even = addSchedule(teacher, courseId, 2, 1, 2, 1, 16, "EVEN");
        assertThat(courseDetail(teacher, courseId).at("/schedules").size()).isEqualTo(2);

        // 但 ALL 与 ODD 冲突
        mvc.perform(post("/api/v1/courses/" + courseId + "/schedules")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody(2, 1, 2, 1, 16, "ALL"))))
                .andExpect(status().isUnprocessableEntity());

        // 两条单双周排课都删除后，ALL 可以占用该时段
        mvc.perform(delete("/api/v1/courses/" + courseId + "/schedules/" + even)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/courses/" + courseId + "/schedules/" + odd)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk());
        addSchedule(teacher, courseId, 2, 1, 2, 1, 16, "ALL");
    }

    @Test
    void teacherTimetableConflictAcrossCoursesIsRejected() throws Exception {
        SysUser admin = createUser("sched_tt_adm", Roles.ADMIN);
        SysUser teacher = createUser("sched_tt_tea", Roles.TEACHER);
        long semesterId = createActiveSemester(admin);
        String codeA = "TT1-" + suffix();
        long courseA = createCourse(teacher, semesterId, codeA, 10);
        long courseB = createCourse(teacher, semesterId, "TT2-" + suffix(), 10);
        addSchedule(teacher, courseA, 3, 1, 2);

        mvc.perform(post("/api/v1/courses/" + courseB + "/schedules")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody(3, 2, 3, 1, 16, "ALL"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COURSE_SCHEDULE_CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(codeA)));

        // 换一天就不冲突
        addSchedule(teacher, courseB, 4, 1, 2);

        // 另一门课由别的教师承担时不受影响
        SysUser other = createUser("sched_tt_other", Roles.TEACHER);
        long courseC = createCourse(other, semesterId, "TT3-" + suffix(), 10);
        addSchedule(other, courseC, 3, 1, 2);
    }

    @Test
    void myScheduleMergesSelectedAndTeachingCourses() throws Exception {
        SysUser admin = createUser("sched_my_adm", Roles.ADMIN);
        SysUser teacher = createUser("sched_my_tea", Roles.TEACHER);
        SysUser student = createUser("sched_my_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "MY-" + suffix(), 10, 1, 1, 2);
        selectCourse(student, courseId);

        MvcResult studentSchedule = mvc.perform(get("/api/v1/schedules/me")
                        .param("semesterId", Long.toString(semesterId))
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = body(studentSchedule).get("data");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.at("/0/courseId").asLong()).isEqualTo(courseId);
        assertThat(items.at("/0/dayOfWeek").asInt()).isEqualTo(1);
        assertThat(items.at("/0/teacherNames").size()).isEqualTo(1);

        MvcResult teacherSchedule = mvc.perform(get("/api/v1/schedules/me")
                        .param("semesterId", Long.toString(semesterId))
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(teacherSchedule).get("data").size()).isEqualTo(1);

        // 其它学期的筛选结果为空
        long otherSemester = createActiveSemester(admin);
        MvcResult filtered = mvc.perform(get("/api/v1/schedules/me")
                        .param("semesterId", Long.toString(otherSemester))
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(filtered).get("data").size()).isZero();
    }

    @Test
    void scheduleWritesRequireCourseManagePermission() throws Exception {
        SysUser admin = createUser("sched_perm_adm", Roles.ADMIN);
        SysUser teacher = createUser("sched_perm_tea", Roles.TEACHER);
        SysUser other = createUser("sched_perm_other", Roles.TEACHER);
        SysUser student = createUser("sched_perm_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createCourse(teacher, semesterId, "PM-" + suffix(), 10);

        mvc.perform(post("/api/v1/courses/" + courseId + "/schedules")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody(1, 1, 2, 1, 16, "ALL"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/courses/" + courseId + "/schedules")
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody(1, 1, 2, 1, 16, "ALL"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/courses/99999999/schedules")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(scheduleBody(1, 1, 2, 1, 16, "ALL"))))
                .andExpect(status().isNotFound());
    }

    private static Map<String, Object> scheduleBody(int dayOfWeek, int startPeriod, int endPeriod,
                                                    int startWeek, int endWeek, String parity) {
        Map<String, Object> body = new HashMap<>();
        body.put("dayOfWeek", dayOfWeek);
        body.put("startPeriod", startPeriod);
        body.put("endPeriod", endPeriod);
        body.put("startWeek", startWeek);
        body.put("endWeek", endWeek);
        body.put("weekParity", parity);
        body.put("classroom", "B101");
        return body;
    }
}
