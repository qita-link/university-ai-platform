package com.campus.platform.knowledge.importer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.knowledge.config.KnowledgeImportProperties;
import com.campus.platform.knowledge.config.KnowledgeProperties;
import com.campus.platform.knowledge.dto.CategoryNode;
import com.campus.platform.knowledge.dto.CategoryRequest;
import com.campus.platform.knowledge.dto.SpaceCreateRequest;
import com.campus.platform.knowledge.dto.SpaceView;
import com.campus.platform.knowledge.entity.KbCategory;
import com.campus.platform.knowledge.entity.KbSpace;
import com.campus.platform.knowledge.entity.SpaceStatus;
import com.campus.platform.knowledge.entity.SpaceVisibility;
import com.campus.platform.knowledge.mapper.KbCategoryMapper;
import com.campus.platform.knowledge.mapper.KbSpaceMapper;
import com.campus.platform.knowledge.service.KnowledgeCategoryService;
import com.campus.platform.knowledge.service.KnowledgeDocumentService;
import com.campus.platform.knowledge.service.KnowledgeSpaceService;
import com.campus.platform.knowledge.service.MarkdownParser;
import com.campus.platform.security.AuthenticatedUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地 Markdown 语料导入。
 * <p>
 * 用于把已有文档站（例如 SJTU Wiki）灌入知识库，不是生产环境的通用文件接口。
 * 每个文件独立事务，按 source_path + content_hash 幂等；内容变化时生成新版本。
 */
@Slf4j
@Service
public class KnowledgeImportService {

    private final KnowledgeCategoryService categoryService;
    private final KnowledgeSpaceService spaceService;
    private final KnowledgeDocumentService documentService;
    private final KbCategoryMapper categoryMapper;
    private final KbSpaceMapper spaceMapper;
    private final KnowledgeProperties knowledgeProperties;
    private final KnowledgeImportProperties properties;

    public KnowledgeImportService(KnowledgeCategoryService categoryService, KnowledgeSpaceService spaceService,
                                  KnowledgeDocumentService documentService, KbCategoryMapper categoryMapper,
                                  KbSpaceMapper spaceMapper, KnowledgeProperties knowledgeProperties,
                                  KnowledgeImportProperties properties) {
        this.categoryService = categoryService;
        this.spaceService = spaceService;
        this.documentService = documentService;
        this.categoryMapper = categoryMapper;
        this.spaceMapper = spaceMapper;
        this.knowledgeProperties = knowledgeProperties;
        this.properties = properties;
    }

    public record ImportReport(int scanned, int imported, int skipped, int failed, List<String> errors) {
    }

    public ImportReport importCorpus(AuthenticatedUser owner, Path root) {
        if (!Files.isDirectory(root)) {
            throw BusinessException.badRequest("语料目录不存在");
        }
        long categoryId = findOrCreateCategory(owner);
        long spaceId = findOrCreateSpace(owner, categoryId);

        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .filter(p -> {
                        String relative = root.relativize(p).toString().replace('\\', '/');
                        return !relative.startsWith(".vitepress/")
                                && !relative.contains("/.vitepress/")
                                && !relative.contains("node_modules/");
                    })
                    .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                    .toList();
        } catch (IOException ex) {
            throw BusinessException.badRequest("读取语料目录失败: " + ex.getMessage());
        }

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            try {
                long size = Files.size(file);
                if (size > knowledgeProperties.maxFileBytes()) {
                    failed++;
                    errors.add(relative + ": 超过文件大小限制");
                    continue;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.startsWith("\uFEFF")) {
                    content = content.substring(1);
                }
                MarkdownParser.ParsedMarkdown parsed = MarkdownParser.parse(content);
                if (parsed.body() == null || parsed.body().isBlank()) {
                    skipped++;
                    continue;
                }
                String title = parsed.title();
                if (title == null || title.isBlank()) {
                    title = file.getFileName().toString().replaceFirst("(?i)\\.md$", "");
                }
                // 存正文而不是原文：文档站的 frontmatter（title/order）是站点元数据，
                // 留在正文里会变成每篇开头的一段 "title: … order: …"，也会污染切块与检索。
                KnowledgeDocumentService.ImportResult result = documentService.importMarkdown(
                        owner, spaceId, relative, title, parsed.body(), file.getFileName().toString(), "本地语料导入");
                if (result.changed()) {
                    imported++;
                } else {
                    skipped++;
                }
            } catch (BusinessException ex) {
                failed++;
                errors.add(relative + ": " + ex.getMessage());
            } catch (RuntimeException | IOException ex) {
                failed++;
                errors.add(relative + ": 导入失败");
                log.error("导入语料文件失败: {}", relative, ex);
            }
        }
        log.info("知识库语料导入完成: dir={}, category={}, space={}, scanned={}, imported={}, skipped={}, failed={}",
                root, properties.categoryName(), properties.spaceName(), files.size(), imported, skipped, failed);
        return new ImportReport(files.size(), imported, skipped, failed, List.copyOf(errors));
    }

    private long findOrCreateCategory(AuthenticatedUser owner) {
        List<KbCategory> existing = categoryMapper.selectList(new LambdaQueryWrapper<KbCategory>()
                .isNull(KbCategory::getParentId)
                .eq(KbCategory::getName, properties.categoryName())
                .orderByAsc(KbCategory::getId));
        if (!existing.isEmpty()) {
            return existing.get(0).getId();
        }
        CategoryNode created = categoryService.create(owner,
                new CategoryRequest(null, properties.categoryName(), "SJTU Wiki 导入分类", 0));
        return created.id();
    }

    private long findOrCreateSpace(AuthenticatedUser owner, long categoryId) {
        List<KbSpace> existing = spaceMapper.selectList(new LambdaQueryWrapper<KbSpace>()
                .eq(KbSpace::getName, properties.spaceName())
                .eq(KbSpace::getVisibility, SpaceVisibility.PUBLIC)
                .ne(KbSpace::getStatus, SpaceStatus.DELETED)
                .orderByAsc(KbSpace::getId));
        if (!existing.isEmpty()) {
            return existing.get(0).getId();
        }
        SpaceView created = spaceService.create(owner,
                new SpaceCreateRequest(categoryId, properties.spaceName(),
                        "上海交通大学校园百科（SJTU Wiki）本地语料", "PUBLIC", null, null));
        return created.id();
    }
}
