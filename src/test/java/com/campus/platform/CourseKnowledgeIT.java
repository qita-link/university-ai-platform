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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段六：打通阶段三遗留的 {@code kb_space.visibility=COURSE} 可见范围。
 * 任课教师与已选学生可读；退课后立即失去访问权。
 */
class CourseKnowledgeIT extends CourseTestSupport {

    @Test
    void courseSpaceVisibilityFollowsTeachingAndEnrollment() throws Exception {
        SysUser admin = createUser("cks_adm", Roles.ADMIN);
        SysUser teacher = createUser("cks_tea", Roles.TEACHER);
        SysUser student = createUser("cks_stu", Roles.STUDENT);
        SysUser outsider = createUser("cks_out", Roles.STUDENT);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "KS-" + suffix(), 10, 1, 1, 2);
        long selectionId = selectCourse(student, courseId);

        String name = "课程知识库" + suffix();
        long spaceId = createCourseSpace(teacher, courseId, name);

        // 教师与已选学生可读
        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visibility").value("COURSE"))
                .andExpect(jsonPath("$.data.scopeCourseId").value(courseId))
                .andExpect(jsonPath("$.data.scopeCourseName").value(org.hamcrest.Matchers.containsString("KS-")));
        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessLevel").value("COURSE"));

        // 未选课的学生看不到
        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId)
                        .header("Authorization", bearer(outsider)))
                .andExpect(status().isNotFound());
        assertThat(listSpaceNames(outsider)).doesNotContain(name);
        assertThat(listSpaceNames(student)).contains(name);

        // 退课后立即失去访问权
        mvc.perform(delete("/api/v1/course-selections/" + selectionId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isNotFound());
        assertThat(listSpaceNames(student)).doesNotContain(name);

        // 管理员始终可读
        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void courseSpaceCreationRequiresTeachingRelation() throws Exception {
        SysUser admin = createUser("cks2_adm", Roles.ADMIN);
        SysUser teacher = createUser("cks2_tea", Roles.TEACHER);
        SysUser other = createUser("cks2_other", Roles.TEACHER);
        long semesterId = createActiveSemester(admin);
        long courseId = createOpenCourse(teacher, semesterId, "KS2-" + suffix(), 10, 2, 1, 2);

        // 非任课教师不能为别人的课程建知识库
        mvc.perform(post("/api/v1/knowledge/spaces")
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(spaceBody(courseId, "越权课程库" + suffix()))))
                .andExpect(status().isForbidden());

        // 缺少课程范围
        Map<String, Object> missing = spaceBody(null, "缺少课程" + suffix());
        mvc.perform(post("/api/v1/knowledge/spaces")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(missing)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("KB_SPACE_INVALID_VISIBILITY"));

        // 课程不存在
        mvc.perform(post("/api/v1/knowledge/spaces")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(spaceBody(99999999L, "课程不存在" + suffix()))))
                .andExpect(status().isNotFound());

        // 课程停开后不能建库
        changeCourseStatus(teacher, courseId, "CANCELLED");
        mvc.perform(post("/api/v1/knowledge/spaces")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(spaceBody(courseId, "停开课程" + suffix()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("KB_SPACE_INVALID_VISIBILITY"));

        // 管理员可以为任意课程建库
        long otherCourse = createOpenCourse(other, semesterId, "KS3-" + suffix(), 10, 3, 1, 2);
        createCourseSpace(admin, otherCourse, "管理员建的课程库" + suffix());
    }

    private long createCourseSpace(SysUser owner, long courseId, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/knowledge/spaces")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(spaceBody(courseId, name))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(name))
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    private java.util.List<String> listSpaceNames(SysUser viewer) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/knowledge/spaces")
                        .param("size", "100")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andReturn();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonNode item : body(result).at("/data/items")) {
            names.add(item.get("name").asText());
        }
        return names;
    }

    private static Map<String, Object> spaceBody(Long courseId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", "课程知识库测试");
        body.put("visibility", "COURSE");
        if (courseId != null) {
            body.put("scopeCourseId", courseId);
        }
        return body;
    }
}
