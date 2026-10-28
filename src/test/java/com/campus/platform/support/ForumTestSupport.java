package com.campus.platform.support;

import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 论坛集成测试的公共操作：建分类、发帖、评论、按条件查询列表。
 * 所有数据都带随机后缀，测试之间不依赖全表计数。
 */
public abstract class ForumTestSupport extends IntegrationTestBase {

    protected long createCategory(SysUser admin, String name) throws Exception {
        return createCategory(admin, name, null);
    }

    protected long createCategory(SysUser admin, String name, String status) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", "论坛测试分类");
        body.put("sortOrder", 0);
        if (status != null) {
            body.put("status", status);
        }
        MvcResult result = mvc.perform(post("/api/v1/forum/categories")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(name))
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    protected long createPost(SysUser author, long categoryId, String title, String content) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/forum/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(postBody(categoryId, title, content))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(title))
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    protected long createComment(SysUser author, long postId, Long parentId, String content) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        MvcResult result = mvc.perform(post("/api/v1/forum/posts/" + postId + "/comments")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    protected JsonNode postDetail(SysUser viewer, long postId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/forum/posts/" + postId)
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data");
    }

    /** 创建帖子请求体，便于测试直接构造修改/非法请求。 */
    protected static Map<String, Object> postBody(long categoryId, String title, String content) {
        Map<String, Object> body = new HashMap<>();
        body.put("categoryId", categoryId);
        body.put("title", title);
        body.put("content", content);
        return body;
    }

    /** GET /forum/posts，参数按 key/value 成对传入。 */
    protected JsonNode listPosts(SysUser viewer, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/forum/posts")
                .header("Authorization", bearer(viewer));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        MvcResult result = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data");
    }

    protected long commentCount(SysUser viewer, long postId) throws Exception {
        return postDetail(viewer, postId).at("/commentCount").asLong();
    }
}
