package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.CourseTestSupport;
import com.campus.platform.user.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段八：故障与错误映射。
 * <p>
 * 关注两件事：
 * <ol>
 *   <li>业务失败不能留下副作用（失败选课不占名额）；</li>
 *   <li>可预期的业务失败一律映射为 4xx，不能冒泡成 500（避免泄露堆栈、避免前端无法区分）。</li>
 * </ol>
 */
class FailureModeIT extends CourseTestSupport {

    @Test
    void failedSelectionDoesNotConsumeCapacity() throws Exception {
        SysUser admin = createUser("fm_cap_adm", Roles.ADMIN);
        SysUser teacherA = createUser("fm_cap_tea_a", Roles.TEACHER);
        SysUser teacherB = createUser("fm_cap_tea_b", Roles.TEACHER);
        SysUser student = createUser("fm_cap_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseA = createOpenCourse(teacherA, semesterId, "FM1-" + suffix(), 5, 1, 1, 2);
        long courseB = createOpenCourse(teacherB, semesterId, "FM2-" + suffix(), 5, 1, 1, 2);

        selectCourse(student, courseA);
        assertThat(courseDetail(teacherA, courseA).get("selectedCount").asInt()).isEqualTo(1);

        // 时间冲突：拒绝，且不占用 B 的名额
        mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseB))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(courseDetail(teacherB, courseB).get("selectedCount").asInt()).isZero();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_selection WHERE course_id = ?", Integer.class, courseB);
        assertThat(rows).isZero();

        // 重复选课：拒绝，A 的名额仍是 1
        mvc.perform(post("/api/v1/course-selections")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseA))))
                .andExpect(status().isConflict());
        assertThat(courseDetail(teacherA, courseA).get("selectedCount").asInt()).isEqualTo(1);
    }

    @Test
    void businessFailuresAreMappedToClientErrors() throws Exception {
        SysUser admin = createUser("fm_map_adm", Roles.ADMIN);
        SysUser teacher = createUser("fm_map_tea", Roles.TEACHER);
        SysUser student = createUser("fm_map_stu", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        String code = "FM3-" + suffix();
        long courseId = createCourse(teacher, semesterId, code, 10);

        List<Integer> statuses = new ArrayList<>();

        // 同学期课程编码重复 → 409
        statuses.add(mvc.perform(post("/api/v1/courses")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("semesterId", semesterId, "code", code, "name", "重复课程",
                                "credit", 3.0, "capacity", 10))))
                .andReturn().getResponse().getStatus());

        // 未排课就开放 → 422
        statuses.add(mvc.perform(patch("/api/v1/courses/" + courseId + "/status")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "OPEN"))))
                .andReturn().getResponse().getStatus());

        // 请假时间超出学期范围 → 422
        addSchedule(teacher, courseId, 4, 1, 2);
        changeCourseStatus(teacher, courseId, "OPEN");
        selectCourse(student, courseId);
        statuses.add(mvc.perform(post("/api/v1/leave-requests")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("courseId", courseId,
                                "startAt", LocalDateTime.of(LocalDate.now().minusDays(90), LocalTime.of(9, 0)).toString(),
                                "endAt", LocalDateTime.of(LocalDate.now().minusDays(90), LocalTime.of(10, 0)).toString(),
                                "reason", "上学期的时间"))))
                .andReturn().getResponse().getStatus());

        // 论坛：父评论不属于该帖子 → 422
        long categoryId = createForumCategory(admin, "故障" + suffix());
        long post1 = createForumPost(student, categoryId, "帖子一" + suffix(), "正文");
        long post2 = createForumPost(student, categoryId, "帖子二" + suffix(), "正文");
        MvcResult comment = mvc.perform(post("/api/v1/forum/posts/" + post1 + "/comments")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "评论"))))
                .andReturn();
        long commentId = body(comment).at("/data/id").asLong();
        statuses.add(mvc.perform(post("/api/v1/forum/posts/" + post2 + "/comments")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "跨帖回复", "parentId", commentId))))
                .andReturn().getResponse().getStatus());

        // 工具：未知工具 404、参数非法 400
        statuses.add(invokeTool(student, "noSuchTool", Map.of()).andReturn().getResponse().getStatus());
        statuses.add(invokeTool(student, "selectCourse", Map.of()).andReturn().getResponse().getStatus());

        // 不存在的资源 → 404
        statuses.add(mvc.perform(get("/api/v1/courses/99999999").header("Authorization", bearer(student)))
                .andReturn().getResponse().getStatus());

        assertThat(statuses).allSatisfy(status -> assertThat(status)
                .as("可预期的业务失败必须是 4xx")
                .isBetween(400, 499));
        assertThat(statuses).contains(400, 404, 409, 422);
    }

    private ResultActions invokeTool(SysUser user, String tool, Map<String, Object> arguments) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("tool", tool);
        body.put("arguments", arguments);
        return mvc.perform(post("/api/v1/ai/tool-calls")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private long createForumCategory(SysUser admin, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/forum/categories")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "sortOrder", 0))))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    private long createForumPost(SysUser author, long categoryId, String title, String content) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/forum/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("categoryId", categoryId, "title", title, "content", content))))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).at("/data/id").asLong();
    }
}
