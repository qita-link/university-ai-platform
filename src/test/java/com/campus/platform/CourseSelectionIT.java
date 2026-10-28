package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.CourseTestSupport;
import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 阶段六：选课、退课、重复/容量/时间冲突、名单权限与并发防超卖。 */
class CourseSelectionIT extends CourseTestSupport {

    @Test
    void selectDropAndReselectKeepCapacityConsistent() throws Exception {
        SysUser admin = createUser("sel_adm", Roles.ADMIN);
        SysUser teacher = createUser("sel_tea", Roles.TEACHER);
        SysUser student = createUser("sel_stu", Roles.STUDENT);
        SysUser other = createUser("sel_other", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "SEL-" + suffix(), 2, 1, 1, 2);

        long selectionId = selectCourse(student, courseId);
        assertThat(courseDetail(teacher, courseId).get("selectedCount").asInt()).isEqualTo(1);
        JsonNode mine = mySelections(student);
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.at("/0/courseId").asLong()).isEqualTo(courseId);
        assertThat(mine.at("/0/status").asText()).isEqualTo("SELECTED");

        // 他人不能退我的课
        mvc.perform(delete("/api/v1/course-selections/" + selectionId)
                        .header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());

        // 退课：名额释放，记录不再出现在我的选课里
        mvc.perform(delete("/api/v1/course-selections/" + selectionId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        assertThat(courseDetail(teacher, courseId).get("selectedCount").asInt()).isZero();
        assertThat(mySelections(student).size()).isZero();

        // 重复退课 → 404
        mvc.perform(delete("/api/v1/course-selections/" + selectionId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isNotFound());

        // 退课后可以重新选（唯一约束只限制有效选课）
        long reselected = selectCourse(student, courseId);
        assertThat(reselected).isNotEqualTo(selectionId);
        assertThat(courseDetail(teacher, courseId).get("selectedCount").asInt()).isEqualTo(1);
    }

    @Test
    void duplicateSelectionAndFullCourseAreRejected() throws Exception {
        SysUser admin = createUser("sel_dup_adm", Roles.ADMIN);
        SysUser teacher = createUser("sel_dup_tea", Roles.TEACHER);
        SysUser a = createUser("sel_dup_a", Roles.STUDENT);
        SysUser b = createUser("sel_dup_b", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "DUP-" + suffix(), 1, 2, 1, 2);

        selectCourse(a, courseId);
        mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELECTION_DUPLICATED"));

        mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(b))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseId))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SELECTION_COURSE_FULL"));
        assertThat(courseDetail(teacher, courseId).get("selectedCount").asInt()).isEqualTo(1);
    }

    @Test
    void timeConflictIsRejectedButOddEvenWeeksCanCoexist() throws Exception {
        SysUser admin = createUser("sel_tc_adm", Roles.ADMIN);
        SysUser teacher = createUser("sel_tc_tea", Roles.TEACHER);
        SysUser teacher2 = createUser("sel_tc_tea2", Roles.TEACHER);
        SysUser student = createUser("sel_tc_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);

        String codeA = "TC1-" + suffix();
        long courseA = createOpenCourse(teacher, semesterId, codeA, 10, 1, 1, 2);
        // 另一位教师承担，避免排课阶段触发“教师课表冲突”
        long courseB = createOpenCourse(teacher2, semesterId, "TC2-" + suffix(), 10, 1, 2, 3);

        // 单双周互斥：同一时间段的单周课与双周课不算冲突
        long odd = createCourse(teacher, semesterId, "TC3-" + suffix(), 10);
        addSchedule(teacher, odd, 5, 1, 2, 1, 16, "ODD");
        changeCourseStatus(teacher, odd, "OPEN");
        long even = createCourse(teacher, semesterId, "TC4-" + suffix(), 10);
        addSchedule(teacher, even, 5, 1, 2, 1, 16, "EVEN");
        changeCourseStatus(teacher, even, "OPEN");

        selectCourse(student, courseA);
        mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseB))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SELECTION_TIME_CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(codeA)));

        // 单双周互斥的课程不算冲突
        selectCourse(student, odd);
        selectCourse(student, even);
        assertThat(mySelections(student).size()).isEqualTo(3);
    }

    @Test
    void concurrentSelectionsNeverOversell() throws Exception {
        SysUser admin = createUser("sel_cc_adm", Roles.ADMIN);
        SysUser teacher = createUser("sel_cc_tea", Roles.TEACHER);
        long semesterId = createActiveSemester(admin);
        int capacity = 3;
        long courseId = createOpenCourse(teacher, semesterId, "CC-" + suffix(), capacity, 4, 1, 2);

        int students = 8;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < students; i++) {
            tokens.add(bearer(createUser("sel_cc_s" + i, Roles.STUDENT)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(students);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (String token : tokens) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/v1/course-selections")
                                    .header("Authorization", token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json(Map.of("courseId", courseId))))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            int ok = 0;
            int full = 0;
            for (Future<Integer> future : futures) {
                int code = future.get(30, TimeUnit.SECONDS);
                if (code == 200) {
                    ok++;
                } else if (code == 422) {
                    full++;
                }
            }
            assertThat(ok).isEqualTo(capacity);
            assertThat(full).isEqualTo(students - capacity);
        } finally {
            pool.shutdownNow();
        }

        JsonNode detail = courseDetail(teacher, courseId);
        assertThat(detail.get("selectedCount").asInt()).isEqualTo(capacity);
    }

    @Test
    void rosterIsVisibleToTeachersAndAdminsOnly() throws Exception {
        SysUser admin = createUser("sel_ro_adm", Roles.ADMIN);
        SysUser teacher = createUser("sel_ro_tea", Roles.TEACHER);
        SysUser other = createUser("sel_ro_other", Roles.TEACHER);
        SysUser a = createUser("sel_ro_a", Roles.STUDENT);
        SysUser b = createUser("sel_ro_b", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "RO-" + suffix(), 10, 3, 1, 2);
        selectCourse(a, courseId);
        selectCourse(b, courseId);

        MvcResult roster = mvc.perform(get("/api/v1/courses/" + courseId + "/selections")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = body(roster).get("data");
        assertThat(data.get("total").asLong()).isEqualTo(2);
        assertThat(data.at("/items/0/displayName").asText()).isNotBlank();
        assertThat(data.at("/items/0/username").asText()).startsWith("sel_ro_");

        mvc.perform(get("/api/v1/courses/" + courseId + "/selections")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/courses/" + courseId + "/selections")
                        .header("Authorization", bearer(a)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/courses/" + courseId + "/selections")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void selectionRequiresSelectionPermission() throws Exception {
        SysUser admin = createUser("sel_pm_adm", Roles.ADMIN);
        SysUser teacher = createUser("sel_pm_tea", Roles.TEACHER);
        SysUser noRole = createUser("sel_pm_none");
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "PM2-" + suffix(), 10, 6, 1, 2);

        mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseId))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(noRole))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseId))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/course-selections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseId))))
                .andExpect(status().isUnauthorized());

        // 选课窗口关闭时不能选课
        SysUser student = createUser("sel_pm_stu", Roles.STUDENT);
        mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", 99999999))))
                .andExpect(status().isNotFound());
    }
}
