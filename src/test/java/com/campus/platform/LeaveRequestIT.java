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

/** 阶段六：请假提交、审批、驳回、撤销与并发审批。 */
class LeaveRequestIT extends CourseTestSupport {

    private static LocalDateTime at(int hour) {
        return LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(hour, 0));
    }

    @Test
    void studentSubmitsAndTeacherApproves() throws Exception {
        SysUser admin = createUser("leave_adm", Roles.ADMIN);
        SysUser teacher = createUser("leave_tea", Roles.TEACHER);
        SysUser student = createUser("leave_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "LV-" + suffix(), 10, 1, 1, 2);
        selectCourse(student, courseId);

        long leaveId = submitLeave(student, courseId, at(10), at(12), "身体不适需要就医");
        JsonNode submitted = leaveDetail(student, leaveId);
        assertThat(submitted.get("status").asText()).isEqualTo("PENDING");
        assertThat(submitted.get("studentName").asText()).isEqualTo(student.getDisplayName());
        assertThat(submitted.get("canCancel").asBoolean()).isTrue();
        assertThat(submitted.get("canReview").asBoolean()).isFalse();

        // 教师待审列表包含该申请
        MvcResult toReview = mvc.perform(get("/api/v1/leave-requests/to-review")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode reviewItems = body(toReview).at("/data/items");
        assertThat(containsLeave(reviewItems, leaveId)).isTrue();
        assertThat(body(toReview).at("/data/items/0/canReview").asBoolean()).isTrue();

        // 通过
        mvc.perform(post("/api/v1/leave-requests/" + leaveId + "/approve")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("comment", "已了解，注意休息"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewComment").value("已了解，注意休息"))
                .andExpect(jsonPath("$.data.reviewerId").value(teacher.getId()));

        MvcResult mine = mvc.perform(get("/api/v1/leave-requests/me")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(mine).at("/data/total").asLong()).isEqualTo(1);
        assertThat(body(mine).at("/data/items/0/status").asText()).isEqualTo("APPROVED");
        assertThat(body(mine).at("/data/items/0/canCancel").asBoolean()).isFalse();
    }

    @Test
    void teacherCannotReviewLeaveOfOtherCourse() throws Exception {
        SysUser admin = createUser("leave_x_adm", Roles.ADMIN);
        SysUser teacher = createUser("leave_x_tea", Roles.TEACHER);
        SysUser other = createUser("leave_x_other", Roles.TEACHER);
        SysUser student = createUser("leave_x_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "LX-" + suffix(), 10, 2, 1, 2);
        selectCourse(student, courseId);
        long leaveId = submitLeave(student, courseId, at(9), at(11), "参加竞赛");

        // 非任课教师既看不到也审批不了
        mvc.perform(get("/api/v1/leave-requests/" + leaveId)
                        .header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/leave-requests/" + leaveId + "/approve")
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("comment", "越权审批"))))
                .andExpect(status().isForbidden());

        // 学生不能审批
        mvc.perform(post("/api/v1/leave-requests/" + leaveId + "/approve")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("comment", "自批"))))
                .andExpect(status().isForbidden());

        // 管理员可以审批任意课程的请假
        mvc.perform(post("/api/v1/leave-requests/" + leaveId + "/approve")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("comment", "管理员审批"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void rejectCancelAndTerminalStateRules() throws Exception {
        SysUser admin = createUser("leave_rc_adm", Roles.ADMIN);
        SysUser teacher = createUser("leave_rc_tea", Roles.TEACHER);
        SysUser student = createUser("leave_rc_stu", Roles.STUDENT);
        SysUser other = createUser("leave_rc_other", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "RC-" + suffix(), 10, 3, 1, 2);
        selectCourse(student, courseId);

        long rejected = submitLeave(student, courseId, at(8), at(10), "事假");
        mvc.perform(post("/api/v1/leave-requests/" + rejected + "/reject")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("comment", "缺少证明材料"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        // 终态不能再改
        mvc.perform(post("/api/v1/leave-requests/" + rejected + "/approve")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("comment", "重复审批"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/leave-requests/" + rejected + "/cancel")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("comment", "想撤销"))))
                .andExpect(status().isConflict());

        // 他人不能撤销
        long pending = submitLeave(student, courseId, at(13), at(15), "临时外出");
        mvc.perform(post("/api/v1/leave-requests/" + pending + "/cancel")
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("comment", "越权撤销"))))
                .andExpect(status().isForbidden());

        // 本人撤销成功
        mvc.perform(post("/api/v1/leave-requests/" + pending + "/cancel")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("comment", "行程取消"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // 撤销后不再出现在待审列表
        MvcResult toReview = mvc.perform(get("/api/v1/leave-requests/to-review")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(containsLeave(body(toReview).at("/data/items"), pending)).isFalse();
    }

    @Test
    void submitValidatesEnrollmentAndTimeRange() throws Exception {
        SysUser admin = createUser("leave_v_adm", Roles.ADMIN);
        SysUser teacher = createUser("leave_v_tea", Roles.TEACHER);
        SysUser teacher2 = createUser("leave_v_tea2", Roles.TEACHER);
        SysUser student = createUser("leave_v_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "LVV-" + suffix(), 10, 4, 1, 2);
        long otherCourse = createOpenCourse(teacher2, semesterId, "LVW-" + suffix(), 10, 5, 1, 2);

        // 未选课
        mvc.perform(post("/api/v1/leave-requests")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(leaveBody(courseId, at(10), at(12), "未选课请假"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SELECTION_NOT_ENROLLED"));

        selectCourse(student, courseId);

        // 结束早于开始
        mvc.perform(post("/api/v1/leave-requests")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(leaveBody(courseId, at(12), at(10), "时间颠倒"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LEAVE_TIME_INVALID"));

        // 超出学期范围
        mvc.perform(post("/api/v1/leave-requests")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(leaveBody(courseId, LocalDateTime.of(LocalDate.now().minusDays(90),
                                LocalTime.of(10, 0)), LocalDateTime.of(LocalDate.now().minusDays(90),
                                LocalTime.of(12, 0)), "上学期的时间"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LEAVE_TIME_INVALID"));

        // 排课不属于该课程
        Map<String, Object> withSchedule = leaveBody(courseId, at(10), at(12), "排课不匹配");
        long otherScheduleId = courseDetail(teacher2, otherCourse).at("/schedules/0/id").asLong();
        withSchedule.put("scheduleId", otherScheduleId);
        mvc.perform(post("/api/v1/leave-requests")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(withSchedule)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LEAVE_TIME_INVALID"));

        // 原因为空
        mvc.perform(post("/api/v1/leave-requests")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(leaveBody(courseId, at(10), at(12), " "))))
                .andExpect(status().isBadRequest());

        // 教师没有 leave:submit，不能提交请假
        mvc.perform(post("/api/v1/leave-requests")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(leaveBody(courseId, at(10), at(12), "教师请假"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentApprovalOnlyOneRequestWins() throws Exception {
        SysUser admin = createUser("leave_cc_adm", Roles.ADMIN);
        SysUser teacher = createUser("leave_cc_tea", Roles.TEACHER);
        SysUser student = createUser("leave_cc_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "LCC-" + suffix(), 10, 6, 1, 2);
        selectCourse(student, courseId);
        long leaveId = submitLeave(student, courseId, at(9), at(12), "并发审批测试");

        List<String> tokens = List.of(bearer(teacher), bearer(admin));
        ExecutorService pool = Executors.newFixedThreadPool(tokens.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (String token : tokens) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/v1/leave-requests/" + leaveId + "/approve")
                                    .header("Authorization", token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json(Map.of("comment", "并发审批"))))
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
            assertThat(conflict).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(leaveDetail(student, leaveId).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void toReviewOnlyContainsOwnCoursesForTeachers() throws Exception {
        SysUser admin = createUser("leave_tr_adm", Roles.ADMIN);
        SysUser teacher = createUser("leave_tr_tea", Roles.TEACHER);
        SysUser teacher2 = createUser("leave_tr_tea2", Roles.TEACHER);
        SysUser student = createUser("leave_tr_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long mine = createOpenCourse(teacher, semesterId, "TR1-" + suffix(), 10, 1, 1, 2);
        long others = createOpenCourse(teacher2, semesterId, "TR2-" + suffix(), 10, 2, 1, 2);
        selectCourse(student, mine);
        selectCourse(student, others);

        long mineLeave = submitLeave(student, mine, at(9), at(10), "课程一请假");
        long othersLeave = submitLeave(student, others, at(11), at(12), "课程二请假");

        MvcResult teacherReview = mvc.perform(get("/api/v1/leave-requests/to-review")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode teacherItems = body(teacherReview).at("/data/items");
        assertThat(containsLeave(teacherItems, mineLeave)).isTrue();
        assertThat(containsLeave(teacherItems, othersLeave)).isFalse();

        MvcResult adminReview = mvc.perform(get("/api/v1/leave-requests/to-review")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode adminItems = body(adminReview).at("/data/items");
        assertThat(containsLeave(adminItems, mineLeave)).isTrue();
        assertThat(containsLeave(adminItems, othersLeave)).isTrue();
    }

    private static boolean containsLeave(JsonNode items, long leaveId) {
        for (JsonNode item : items) {
            if (item.get("id").asLong() == leaveId) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> leaveBody(long courseId, LocalDateTime start, LocalDateTime end,
                                                 String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("courseId", courseId);
        body.put("startAt", start.toString());
        body.put("endAt", end.toString());
        body.put("reason", reason);
        return body;
    }
}
