package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.CourseTestSupport;
import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段八：混合并发场景。
 * <p>
 * 与阶段六、七的单点并发用例互补，这里验证“两个不同操作同时发生”时的最终一致性：
 * 选课与停开课程、同一学生并发重复选课、审批与撤销请假。
 */
class ConcurrencyIT extends CourseTestSupport {

    @Test
    void concurrentSelectionAndCourseCancellationStayConsistent() throws Exception {
        SysUser admin = createUser("cc_mix_adm", Roles.ADMIN);
        SysUser teacher = createUser("cc_mix_tea", Roles.TEACHER);
        long semesterId = createActiveSemester(admin);
        int capacity = 3;
        long courseId = createOpenCourse(teacher, semesterId, "MIX-" + suffix(), capacity, 1, 1, 2);

        int students = 6;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < students; i++) {
            tokens.add(bearer(createUser("cc_mix_s" + i, Roles.STUDENT)));
        }
        String teacherToken = bearer(teacher);

        ExecutorService pool = Executors.newFixedThreadPool(students + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> selects = new ArrayList<>();
        Future<Integer> cancel;
        try {
            for (String token : tokens) {
                selects.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/v1/course-selections")
                                    .header("Authorization", token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json(Map.of("courseId", courseId))))
                            .andReturn().getResponse().getStatus();
                }));
            }
            cancel = pool.submit(() -> {
                start.await();
                return mvc.perform(patch("/api/v1/courses/" + courseId + "/status")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("status", "CANCELLED", "reason", "并发测试"))))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();

            int selected = 0;
            for (Future<Integer> future : selects) {
                if (future.get(30, TimeUnit.SECONDS) == 200) {
                    selected++;
                }
            }
            assertThat(cancel.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            // 停开前最多只能选到容量上限，不会因为并发而超卖
            assertThat(selected).isLessThanOrEqualTo(capacity);
        } finally {
            pool.shutdownNow();
        }

        // 停开是终态：全部有效选课被退掉、计数清零，不会留下“停开课程上仍有选课”的脏数据
        JsonNode detail = courseDetail(teacher, courseId);
        assertThat(detail.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(detail.get("selectedCount").asInt()).isZero();
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_selection WHERE course_id = ? AND status = 'SELECTED'",
                Integer.class, courseId);
        assertThat(active).isZero();
    }

    @Test
    void sameStudentConcurrentSelectionOnlyOneWins() throws Exception {
        SysUser admin = createUser("cc_dup_adm", Roles.ADMIN);
        SysUser teacher = createUser("cc_dup_tea", Roles.TEACHER);
        SysUser student = createUser("cc_dup_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "DUP2-" + suffix(), 10, 2, 1, 2);

        String token = bearer(student);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
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

        // 唯一约束 + 学生行锁共同保证只有一条有效选课，名额也只扣一次
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_selection WHERE course_id = ? AND status = 'SELECTED'",
                Integer.class, courseId);
        assertThat(rows).isEqualTo(1);
        assertThat(courseDetail(teacher, courseId).get("selectedCount").asInt()).isEqualTo(1);
    }

    @Test
    void concurrentApproveAndCancelLeaveOnlyOneWins() throws Exception {
        SysUser admin = createUser("cc_lv_adm", Roles.ADMIN);
        SysUser teacher = createUser("cc_lv_tea", Roles.TEACHER);
        SysUser student = createUser("cc_lv_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "CLV-" + suffix(), 10, 3, 1, 2);
        selectCourse(student, courseId);
        long leaveId = submitLeave(student, courseId,
                LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(9, 0)),
                LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(11, 0)), "并发测试");

        String teacherToken = bearer(teacher);
        String studentToken = bearer(student);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> approve = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/leave-requests/" + leaveId + "/approve")
                                .header("Authorization", teacherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("comment", "同意"))))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> cancel = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/leave-requests/" + leaveId + "/cancel")
                                .header("Authorization", studentToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("comment", "行程取消"))))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();

            List<Integer> statuses = List.of(approve.get(30, TimeUnit.SECONDS), cancel.get(30, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        // 终态二选一，不会出现“既通过又撤销”
        MvcResult detail = mvc.perform(get("/api/v1/leave-requests/" + leaveId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andReturn();
        String status = body(detail).at("/data/status").asText();
        assertThat(status).isIn("APPROVED", "CANCELLED");
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_request WHERE id = ? AND status = 'PENDING'", Integer.class, leaveId);
        assertThat(pending).isZero();
    }
}
