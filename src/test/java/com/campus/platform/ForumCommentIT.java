package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.ForumTestSupport;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 阶段五：评论/两级回复、级联软删除、权限与并发计数。 */
class ForumCommentIT extends ForumTestSupport {

    @Test
    void commentAndNestedReplyProduceTwoLevelThread() throws Exception {
        SysUser admin = createUser("forum_cm_adm", Roles.ADMIN);
        SysUser a = createUser("forum_cm_a", Roles.STUDENT);
        SysUser b = createUser("forum_cm_b", Roles.STUDENT);
        SysUser c = createUser("forum_cm_c", Roles.STUDENT);
        SysUser reader = createUser("forum_cm_reader", Roles.STUDENT);
        long categoryId = createCategory(admin, "评论" + suffix());
        long postId = createPost(a, categoryId, "评论帖子" + suffix(), "正文");

        long c1 = createComment(a, postId, null, "顶级评论");
        long r1 = createComment(b, postId, c1, "回复顶级评论");
        long r2 = createComment(c, postId, r1, "回复一条回复");

        JsonNode threads = commentThreads(reader, postId, "1", "20");
        assertThat(threads.get("total").asLong()).isEqualTo(1);
        assertThat(threads.at("/items/0/comment/id").asLong()).isEqualTo(c1);
        assertThat(threads.at("/items/0/comment/parentId").isNull()).isTrue();
        assertThat(threads.at("/items/0/comment/rootId").isNull()).isTrue();
        assertThat(threads.at("/items/0/replies").size()).isEqualTo(2);
        assertThat(threads.at("/items/0/replies/0/id").asLong()).isEqualTo(r1);
        assertThat(threads.at("/items/0/replies/0/parentId").asLong()).isEqualTo(c1);
        assertThat(threads.at("/items/0/replies/0/rootId").asLong()).isEqualTo(c1);
        assertThat(threads.at("/items/0/replies/0/replyToUserId").asLong()).isEqualTo(a.getId());
        assertThat(threads.at("/items/0/replies/0/replyToUserName").asText()).isEqualTo(a.getDisplayName());
        // 回复“回复”仍然挂在同一个顶级评论下，深度保持两层
        assertThat(threads.at("/items/0/replies/1/id").asLong()).isEqualTo(r2);
        assertThat(threads.at("/items/0/replies/1/parentId").asLong()).isEqualTo(r1);
        assertThat(threads.at("/items/0/replies/1/rootId").asLong()).isEqualTo(c1);
        assertThat(threads.at("/items/0/replies/1/replyToUserId").asLong()).isEqualTo(b.getId());
        assertThat(commentCount(reader, postId)).isEqualTo(3);

        // 顶级评论分页：再发两条顶级评论
        createComment(a, postId, null, "第二条顶级评论");
        createComment(b, postId, null, "第三条顶级评论");
        JsonNode page1 = commentThreads(reader, postId, "1", "2");
        assertThat(page1.get("total").asLong()).isEqualTo(3);
        assertThat(page1.get("pages").asLong()).isEqualTo(2);
        assertThat(page1.get("items").size()).isEqualTo(2);
        JsonNode page2 = commentThreads(reader, postId, "2", "2");
        assertThat(page2.get("items").size()).isEqualTo(1);

        // 回复必须属于同一帖子
        long otherPost = createPost(a, categoryId, "另一个帖子" + suffix(), "正文");
        mvc.perform(post("/api/v1/forum/posts/" + otherPost + "/comments")
                        .header("Authorization", bearer(b))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "跨帖回复", "parentId", c1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORUM_COMMENT_PARENT_INVALID"));
        mvc.perform(post("/api/v1/forum/posts/" + postId + "/comments")
                        .header("Authorization", bearer(b))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "不存在的父评论", "parentId", 99999999))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORUM_COMMENT_PARENT_INVALID"));
    }

    @Test
    void deletingTopLevelCommentCascadesRepliesAndUpdatesCount() throws Exception {
        SysUser admin = createUser("forum_cascade_adm", Roles.ADMIN);
        SysUser a = createUser("forum_cascade_a", Roles.STUDENT);
        SysUser b = createUser("forum_cascade_b", Roles.STUDENT);
        long categoryId = createCategory(admin, "级联删除" + suffix());
        long postId = createPost(a, categoryId, "级联删除帖子" + suffix(), "正文");

        long c1 = createComment(a, postId, null, "会被删除的顶级评论");
        createComment(b, postId, c1, "回复一");
        createComment(b, postId, c1, "回复二");
        assertThat(commentCount(a, postId)).isEqualTo(3);

        mvc.perform(delete("/api/v1/forum/comments/" + c1)
                        .header("Authorization", bearer(a)))
                .andExpect(status().isOk());
        assertThat(commentThreads(a, postId, "1", "20").get("total").asLong()).isZero();
        assertThat(commentCount(a, postId)).isZero();

        // 再次删除已删除评论
        mvc.perform(delete("/api/v1/forum/comments/" + c1)
                        .header("Authorization", bearer(a)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingReplyKeepsSiblingsAndUpdatesCount() throws Exception {
        SysUser admin = createUser("forum_reply_adm", Roles.ADMIN);
        SysUser a = createUser("forum_reply_a", Roles.STUDENT);
        SysUser b = createUser("forum_reply_b", Roles.STUDENT);
        SysUser c = createUser("forum_reply_c", Roles.STUDENT);
        long categoryId = createCategory(admin, "回复删除" + suffix());
        long postId = createPost(a, categoryId, "回复删除帖子" + suffix(), "正文");

        long c1 = createComment(a, postId, null, "顶级评论");
        long r1 = createComment(b, postId, c1, "被删除的回复");
        long r2 = createComment(c, postId, r1, "回复被删除的回复");
        long r3 = createComment(c, postId, c1, "另一条同级回复");
        assertThat(commentCount(a, postId)).isEqualTo(4);

        mvc.perform(delete("/api/v1/forum/comments/" + r1)
                        .header("Authorization", bearer(b)))
                .andExpect(status().isOk());
        JsonNode threads = commentThreads(a, postId, "1", "20");
        assertThat(threads.get("total").asLong()).isEqualTo(1);
        assertThat(threads.at("/items/0/replies").size()).isEqualTo(2);
        assertThat(threads.at("/items/0/replies/0/id").asLong()).isEqualTo(r2);
        assertThat(threads.at("/items/0/replies/0/parentId").asLong()).isEqualTo(r1);
        assertThat(threads.at("/items/0/replies/1/id").asLong()).isEqualTo(r3);
        assertThat(commentCount(a, postId)).isEqualTo(3);

        // 非作者、非版主不能删除；管理员可以删除任意评论
        mvc.perform(delete("/api/v1/forum/comments/" + r2)
                        .header("Authorization", bearer(a)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/forum/comments/" + r2)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(commentCount(a, postId)).isEqualTo(2);

        // 删除顶级评论，剩余回复一并软删除
        mvc.perform(delete("/api/v1/forum/comments/" + c1)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(commentThreads(a, postId, "1", "20").get("total").asLong()).isZero();
        assertThat(commentCount(a, postId)).isZero();
    }

    @Test
    void commentPermissionsAndHiddenPostRules() throws Exception {
        SysUser admin = createUser("forum_cperm_adm", Roles.ADMIN);
        SysUser teacher = createUser("forum_cperm_tea", Roles.TEACHER);
        SysUser author = createUser("forum_cperm_author", Roles.STUDENT);
        SysUser other = createUser("forum_cperm_other", Roles.STUDENT);
        SysUser noRole = createUser("forum_cperm_none");
        long categoryId = createCategory(admin, "评论权限" + suffix());
        long postId = createPost(author, categoryId, "评论权限帖子" + suffix(), "正文");

        mvc.perform(get("/api/v1/forum/posts/" + postId + "/comments"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/forum/posts/" + postId + "/comments")
                        .header("Authorization", bearer(noRole))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "无权限评论"))))
                .andExpect(status().isForbidden());

        MvcResult created = mvc.perform(post("/api/v1/forum/posts/" + postId + "/comments")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "教师评论"))))
                .andExpect(status().isOk())
                .andReturn();
        long commentId = body(created).at("/data/id").asLong();

        // 参数校验
        mvc.perform(post("/api/v1/forum/posts/" + postId + "/comments")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", " "))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/forum/posts/" + postId + "/comments")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "长".repeat(2001)))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/forum/posts/99999999/comments")
                        .header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "不存在的帖子"))))
                .andExpect(status().isNotFound());

        // 非作者不能删；教师没有 forum:moderate 也不能删他人评论；管理员可以
        mvc.perform(delete("/api/v1/forum/comments/" + commentId)
                        .header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());

        // 作者可以删除自己的评论，但教师不能删除作者的评论
        long authorComment = createComment(author, postId, null, "作者评论");
        mvc.perform(delete("/api/v1/forum/comments/" + authorComment)
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/forum/comments/" + authorComment)
                        .header("Authorization", bearer(author)))
                .andExpect(status().isOk());

        // 被隐藏的帖子不接受新评论（包括作者本人）
        mvc.perform(patch("/api/v1/forum/posts/" + postId + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "HIDDEN"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/forum/posts/" + postId + "/comments")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "隐藏后评论"))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/forum/posts/" + postId + "/comments")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "管理员隐藏后评论"))))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/forum/comments/" + commentId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentCommentsDoNotLoseCount() throws Exception {
        SysUser admin = createUser("forum_cc_adm", Roles.ADMIN);
        SysUser author = createUser("forum_cc_author", Roles.STUDENT);
        long categoryId = createCategory(admin, "并发评论" + suffix());
        long postId = createPost(author, categoryId, "并发评论帖子" + suffix(), "正文");

        int count = 6;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        String token = bearer(author);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/v1/forum/posts/" + postId + "/comments")
                                    .header("Authorization", token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json(Map.of("content", "并发评论" + index))))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(commentCount(author, postId)).isEqualTo(count);
        JsonNode threads = commentThreads(author, postId, "1", "20");
        assertThat(threads.get("total").asLong()).isEqualTo(count);
        assertThat(threads.at("/items/0/replies").size()).isZero();
    }

    // ---------------- helpers ----------------

    private JsonNode commentThreads(SysUser viewer, long postId, String page, String size) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/forum/posts/" + postId + "/comments")
                        .param("page", page)
                        .param("size", size)
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data");
    }
}
