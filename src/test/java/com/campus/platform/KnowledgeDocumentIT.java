package com.campus.platform;

import com.campus.platform.security.Roles;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.entity.OrgType;
import com.campus.platform.user.entity.SysOrgUnit;
import com.campus.platform.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段三：Markdown 上传、在线编辑、版本、分块、删除与重处理。
 */
class KnowledgeDocumentIT extends IntegrationTestBase {

    @Test
    void batchUploadHandlesPartialFailureAndCreatesIndexedChunks() throws Exception {
        SysUser admin = createUser("kb_doc_adm", Roles.ADMIN);
        long spaceId = createSpace(admin, "上传" + suffix());

        String markdown = "# 入学准备\n\n## 报到\n\n" + "请携带录取通知书和身份证。".repeat(20);
        MockMultipartFile ok = new MockMultipartFile("files", "preparation.md", "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile bad = new MockMultipartFile("files", "notes.txt", "text/plain",
                "not markdown".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mvc.perform(multipart("/api/v1/knowledge/spaces/" + spaceId + "/documents/batch-upload")
                        .file(ok).file(bad)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.status").value("PARTIAL"))
                .andReturn();
        JsonNode items = body(result).at("/data/items");
        long batchId = body(result).at("/data/batchId").asLong();
        JsonNode okItem = item(items, "preparation.md");
        JsonNode badItem = item(items, "notes.txt");
        assertThat(okItem.get("success").asBoolean()).isTrue();
        assertThat(badItem.get("success").asBoolean()).isFalse();
        long docId = okItem.get("documentId").asLong();
        long failedDocId = badItem.get("documentId").asLong();

        mvc.perform(get("/api/v1/knowledge/upload-batches/" + batchId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.status").value("PARTIAL"));

        mvc.perform(get("/api/v1/knowledge/documents/" + docId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processStatus").value("INDEXED"))
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.chunkCount").value(org.hamcrest.Matchers.greaterThan(0)));
        mvc.perform(get("/api/v1/knowledge/documents/" + failedDocId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.processError").isNotEmpty());

        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId + "/documents")
                        .param("processStatus", "INDEXED")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId + "/documents")
                        .param("processStatus", "FAILED")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        // 验证 V2 的 ngram 全文索引可用（阶段四关键词检索的基础）
        Integer fulltextHits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE MATCH(heading_path, content) AGAINST (? IN BOOLEAN MODE)",
                Integer.class, "报到");
        assertThat(fulltextHits).isPositive();
    }

    @Test
    void editCreatesNewVersionAndStalesOldChunks() throws Exception {
        SysUser admin = createUser("kb_edit_adm", Roles.ADMIN);
        long spaceId = createSpace(admin, "编辑" + suffix());
        long docId = createDocument(admin, spaceId, "编辑测试",
                "# 标题\n\n" + "旧内容。".repeat(80));

        JsonNode oldChunks = chunks(admin, docId);
        assertThat(oldChunks.size()).isPositive();
        String oldChunkText = oldChunks.get(0).get("content").asText();

        Map<String, Object> update = new HashMap<>();
        update.put("title", "编辑后的标题");
        update.put("content", "# 新标题\n\n" + "新内容。".repeat(80));
        update.put("version", 0);
        update.put("changeNote", "第二版");
        mvc.perform(put("/api/v1/knowledge/documents/" + docId).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.title").value("编辑后的标题"));

        JsonNode newChunks = chunks(admin, docId);
        assertThat(newChunks.size()).isPositive();
        assertThat(newChunks.findValuesAsText("content")).noneMatch(c -> c.contains("旧内容"));
        assertThat(newChunks.findValuesAsText("content")).anyMatch(c -> c.contains("新内容"));
        assertThat(oldChunkText).contains("旧内容");

        Integer stale = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE document_id = ? AND status = 'STALE'", Integer.class, docId);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE document_id = ? AND status = 'ACTIVE'", Integer.class, docId);
        assertThat(stale).isPositive();
        assertThat(active).isPositive();

        // 旧 version 再次提交冲突
        mvc.perform(put("/api/v1/knowledge/documents/" + docId).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/knowledge/documents/" + docId + "/versions")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/v1/knowledge/documents/" + docId + "/versions/1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("旧内容")));
    }

    @Test
    void deleteHidesDocumentAndInvalidatesChunks() throws Exception {
        SysUser admin = createUser("kb_del_adm", Roles.ADMIN);
        long spaceId = createSpace(admin, "删除" + suffix());
        long docId = createDocument(admin, spaceId, "删除测试",
                "# 待删除\n\n" + "内容。".repeat(50));

        mvc.perform(delete("/api/v1/knowledge/documents/" + docId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/knowledge/documents/" + docId).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId + "/documents")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId + "/documents")
                        .param("status", "DELETED")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE document_id = ? AND status = 'ACTIVE'", Integer.class, docId);
        Integer stale = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE document_id = ? AND status = 'STALE'", Integer.class, docId);
        assertThat(active).isZero();
        assertThat(stale).isPositive();
    }

    @Test
    void readerCannotWriteButCanReadAndAdminCanWrite() throws Exception {
        SysOrgUnit org = createOrg(null, OrgType.COLLEGE);
        SysUser admin = createUser("kb_perm_adm", Roles.ADMIN);
        SysUser student = createUserInOrg("kb_perm_stu", org.getId(), Roles.STUDENT);
        long spaceId = createSpace(admin, "权限" + suffix());
        long docId = createDocument(admin, spaceId, "公开文档", "# 标题\n\n正文内容");

        mvc.perform(get("/api/v1/knowledge/spaces/" + spaceId + "/documents")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/knowledge/documents/" + docId).header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/knowledge/spaces/" + spaceId + "/documents")
                        .header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "越权", "content", "# 越权"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/knowledge/documents/" + docId).header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
    }

    @Test
    void reprocessRebuildsChunksWithoutNewVersion() throws Exception {
        SysUser admin = createUser("kb_rep_adm", Roles.ADMIN);
        long spaceId = createSpace(admin, "重处理" + suffix());
        long docId = createDocument(admin, spaceId, "重处理测试",
                "# 标题\n\n" + "需要重新切分的内容。".repeat(60));
        int before = chunks(admin, docId).size();
        assertThat(before).isPositive();

        mvc.perform(post("/api/v1/knowledge/documents/" + docId + "/reprocess")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.processStatus").value("INDEXED"));
        assertThat(chunks(admin, docId)).hasSize(before);
    }

    // ---------------- helpers ----------------

    private long createSpace(SysUser admin, String name) throws Exception {
        MvcResult category = mvc.perform(post("/api/v1/knowledge/categories")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "分类-" + suffix()))))
                .andExpect(status().isOk())
                .andReturn();
        long categoryId = body(category).at("/data/id").asLong();
        MvcResult space = mvc.perform(post("/api/v1/knowledge/spaces")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("categoryId", categoryId, "name", name,
                                "description", "文档测试", "visibility", "PUBLIC"))))
                .andExpect(status().isOk())
                .andReturn();
        return body(space).at("/data/id").asLong();
    }

    private long createDocument(SysUser admin, long spaceId, String title, String content) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/knowledge/spaces/" + spaceId + "/documents")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", title, "content", content, "changeNote", "初始版本"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andReturn();
        return body(result).at("/data/id").asLong();
    }

    private JsonNode chunks(SysUser user, long docId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/knowledge/documents/" + docId + "/chunks")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).get("data");
    }

    private static JsonNode item(JsonNode items, String filename) {
        for (JsonNode item : items) {
            if (filename.equals(item.get("filename").asText())) {
                return item;
            }
        }
        throw new AssertionError("未找到上传结果: " + filename);
    }
}
