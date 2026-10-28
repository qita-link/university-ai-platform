package com.campus.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.knowledge.entity.KbDocument;
import com.campus.platform.knowledge.entity.KbDocumentVersion;
import com.campus.platform.knowledge.entity.KbSpace;
import com.campus.platform.knowledge.importer.KnowledgeImportService;
import com.campus.platform.knowledge.mapper.KbDocumentMapper;
import com.campus.platform.knowledge.mapper.KbDocumentVersionMapper;
import com.campus.platform.knowledge.mapper.KbSpaceMapper;
import com.campus.platform.security.Roles;
import com.campus.platform.support.IntegrationTestBase;
import com.campus.platform.user.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段三：本地 Markdown 语料导入的幂等与版本更新。
 */
class KnowledgeImportIT extends IntegrationTestBase {

    @Autowired
    KnowledgeImportService importService;

    @Autowired
    KbSpaceMapper spaceMapper;

    @Autowired
    KbDocumentMapper documentMapper;

    @Autowired
    KbDocumentVersionMapper versionMapper;

    @Test
    void importIsIdempotentAndCreatesNewVersionWhenContentChanges(@TempDir Path temp) throws Exception {
        SysUser admin = createUser("kb_imp_adm", Roles.ADMIN);
        String tag = suffix();
        Path root = temp.resolve(tag);
        Files.createDirectories(root.resolve("freshman"));
        Path guide = root.resolve("freshman/guide.md");
        Path other = root.resolve("other.md");
        Files.writeString(guide, """
                ---
                title: 新生指南
                ---

                # 新生指南

                报到时请携带录取通知书。
                """, StandardCharsets.UTF_8);
        Files.writeString(other, "# 校园生活\n\n食堂和宿舍信息。", StandardCharsets.UTF_8);

        KnowledgeImportService.ImportReport first = importService.importCorpus(principalOf(admin), root);
        assertThat(first.scanned()).isEqualTo(2);
        assertThat(first.imported()).isEqualTo(2);
        assertThat(first.skipped()).isZero();
        assertThat(first.errors()).isEmpty();

        KbSpace space = spaceMapper.selectList(new LambdaQueryWrapper<KbSpace>()
                .eq(KbSpace::getName, "SJTU Wiki")).stream().findFirst().orElseThrow();
        KbDocument imported = documentMapper.selectOne(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getSpaceId, space.getId())
                .eq(KbDocument::getSourcePath, "freshman/guide.md"));
        assertThat(imported).isNotNull();
        assertThat(imported.getTitle()).isEqualTo("新生指南");
        assertThat(imported.getSourceType().name()).isEqualTo("IMPORT");
        assertThat(imported.getCurrentVersionId()).isNotNull();

        KnowledgeImportService.ImportReport second = importService.importCorpus(principalOf(admin), root);
        assertThat(second.imported()).isZero();
        assertThat(second.skipped()).isEqualTo(2);

        Files.writeString(guide, """
                # 新生指南（更新）

                报到时请携带录取通知书和身份证。
                """, StandardCharsets.UTF_8);
        KnowledgeImportService.ImportReport third = importService.importCorpus(principalOf(admin), root);
        assertThat(third.imported()).isEqualTo(1);
        assertThat(third.skipped()).isEqualTo(1);

        KbDocument updated = documentMapper.selectById(imported.getId());
        assertThat(updated.getTitle()).isEqualTo("新生指南（更新）");
        long versions = versionMapper.selectCount(new LambdaQueryWrapper<KbDocumentVersion>()
                .eq(KbDocumentVersion::getDocumentId, imported.getId()));
        assertThat(versions).isEqualTo(2);
        assertThat(updated.getCurrentVersionId()).isNotEqualTo(imported.getCurrentVersionId());
        Integer activeChunks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE document_id = ? AND status = 'ACTIVE'",
                Integer.class, imported.getId());
        assertThat(activeChunks).isPositive();
    }
}
