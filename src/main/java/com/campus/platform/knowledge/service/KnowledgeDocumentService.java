package com.campus.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.platform.audit.service.AuditEvent;
import com.campus.platform.audit.service.AuditService;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import com.campus.platform.knowledge.config.KnowledgeProperties;
import com.campus.platform.knowledge.parser.DocumentTextExtractor;
import com.campus.platform.knowledge.dto.ChunkView;
import com.campus.platform.knowledge.dto.DocumentCreateRequest;
import com.campus.platform.knowledge.dto.DocumentDetailView;
import com.campus.platform.knowledge.dto.DocumentQuery;
import com.campus.platform.knowledge.dto.DocumentSummaryView;
import com.campus.platform.knowledge.dto.DocumentUpdateRequest;
import com.campus.platform.knowledge.dto.UploadBatchView;
import com.campus.platform.knowledge.dto.UploadItemResult;
import com.campus.platform.knowledge.dto.VersionDetailView;
import com.campus.platform.knowledge.dto.VersionView;
import com.campus.platform.knowledge.entity.ChunkStatus;
import com.campus.platform.knowledge.entity.DocumentSource;
import com.campus.platform.knowledge.entity.DocumentStatus;
import com.campus.platform.knowledge.entity.EmbeddingStatus;
import com.campus.platform.knowledge.entity.KbChunk;
import com.campus.platform.knowledge.entity.KbDocument;
import com.campus.platform.knowledge.entity.KbDocumentVersion;
import com.campus.platform.knowledge.entity.KbSpace;
import com.campus.platform.knowledge.entity.KbUploadBatch;
import com.campus.platform.knowledge.entity.BatchStatus;
import com.campus.platform.knowledge.entity.ProcessStatus;
import com.campus.platform.knowledge.mapper.KbChunkMapper;
import com.campus.platform.knowledge.mapper.KbDocumentMapper;
import com.campus.platform.knowledge.mapper.KbDocumentVersionMapper;
import com.campus.platform.knowledge.mapper.KbUploadBatchMapper;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import com.campus.platform.user.entity.SysUser;
import com.campus.platform.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库文档：上传、在线编辑、版本、分块、软删除与重处理。
 * <p>
 * 每次创建/编辑都生成不可变版本；编辑或删除时把旧块标记为 STALE，检索只使用当前版本的 ACTIVE 块。
 * 批量上传逐文件使用独立事务，单个文件失败不会回滚其他文件。
 */
@Slf4j
@Service
public class KnowledgeDocumentService {

    static final String MODULE = "knowledge";

    private final KbDocumentMapper documentMapper;
    private final KbDocumentVersionMapper versionMapper;
    private final KbChunkMapper chunkMapper;
    private final KbUploadBatchMapper batchMapper;
    private final SysUserMapper userMapper;
    private final KnowledgeAccessService accessService;
    private final MarkdownChunker chunker;
    private final KnowledgeProperties properties;
    private final TransactionTemplate tx;
    private final AuditService auditService;
    private final Clock clock;
    private final DocumentTextExtractor extractor;

    public KnowledgeDocumentService(KbDocumentMapper documentMapper, KbDocumentVersionMapper versionMapper,
                                    KbChunkMapper chunkMapper, KbUploadBatchMapper batchMapper,
                                    SysUserMapper userMapper, KnowledgeAccessService accessService,
                                    MarkdownChunker chunker, KnowledgeProperties properties,
                                    TransactionTemplate tx, AuditService auditService, Clock clock,
                                    DocumentTextExtractor extractor) {
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.chunkMapper = chunkMapper;
        this.batchMapper = batchMapper;
        this.userMapper = userMapper;
        this.accessService = accessService;
        this.chunker = chunker;
        this.properties = properties;
        this.tx = tx;
        this.auditService = auditService;
        this.clock = clock;
        this.extractor = extractor;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public PageResult<DocumentSummaryView> list(AuthenticatedUser user, long spaceId, DocumentQuery q) {
        KbSpace space = accessService.requireReadable(user, spaceId);
        DocumentStatus status = q.getStatus() == null ? DocumentStatus.ACTIVE : q.getStatus();
        if (status != DocumentStatus.ACTIVE && !accessService.canManage(user, space)
                && !user.hasPermission(Permissions.KB_SPACE_MANAGE_ALL)) {
            throw BusinessException.forbidden("只有管理者可以查看已删除文档");
        }
        LambdaQueryWrapper<KbDocument> w = new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getSpaceId, spaceId)
                .eq(KbDocument::getStatus, status)
                .eq(q.getProcessStatus() != null, KbDocument::getProcessStatus, q.getProcessStatus());
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            String kw = escapeLike(q.getKeyword().trim());
            w.and(x -> x.like(KbDocument::getTitle, kw).or().like(KbDocument::getOriginalFilename, kw));
        }
        w.orderByDesc(KbDocument::getId);
        IPage<KbDocument> page = documentMapper.selectPage(q.toPage(), w);

        List<KbDocument> docs = page.getRecords();
        Map<Long, KbDocumentVersion> versions = versionsOf(docs.stream()
                .map(KbDocument::getCurrentVersionId).toList());
        Map<Long, String> uploaders = namesOfUsers(docs.stream().map(KbDocument::getUploadedBy).toList());
        return PageResult.of(page, d -> summary(d, versions.get(d.getCurrentVersionId()),
                uploaders.get(d.getUploadedBy()), chunkMapper.countActiveByDocument(d.getId())));
    }

    public DocumentDetailView get(AuthenticatedUser user, long id) {
        KbDocument doc = requireVisibleDocument(user, id);
        KbDocumentVersion version = doc.getCurrentVersionId() == null ? null
                : versionMapper.selectById(doc.getCurrentVersionId());
        SysUser uploader = userMapper.selectById(doc.getUploadedBy());
        return detail(doc, version, uploader == null ? null : uploader.getDisplayName(),
                chunkMapper.countActiveByDocument(doc.getId()));
    }

    public List<ChunkView> chunks(AuthenticatedUser user, long id) {
        KbDocument doc = requireVisibleDocument(user, id);
        if (doc.getCurrentVersionId() == null) {
            return List.of();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getVersionId, doc.getCurrentVersionId())
                        .orderByAsc(KbChunk::getChunkIndex))
                .stream().map(c -> new ChunkView(c.getId(), c.getVersionId(), c.getChunkIndex(),
                        c.getHeadingPath(), c.getContent(), c.getCharCount(), c.getTokenEstimate(),
                        c.getStatus(), c.getEmbeddingStatus(), c.getVectorId()))
                .toList();
    }

    public List<VersionView> versions(AuthenticatedUser user, long id) {
        KbDocument doc = requireVisibleDocument(user, id);
        List<KbDocumentVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<KbDocumentVersion>()
                .eq(KbDocumentVersion::getDocumentId, id)
                .orderByDesc(KbDocumentVersion::getVersionNo));
        Map<Long, String> names = namesOfUsers(versions.stream().map(KbDocumentVersion::getCreatedBy).toList());
        return versions.stream().map(v -> new VersionView(v.getId(), v.getVersionNo(), v.getContentHash(),
                v.getSizeBytes() == null ? 0 : v.getSizeBytes(), v.getChangeNote(), v.getCreatedBy(),
                names.get(v.getCreatedBy()), v.getCreatedAt())).toList();
    }

    public VersionDetailView version(AuthenticatedUser user, long id, int versionNo) {
        KbDocument doc = requireVisibleDocument(user, id);
        KbDocumentVersion v = versionMapper.selectOne(new LambdaQueryWrapper<KbDocumentVersion>()
                .eq(KbDocumentVersion::getDocumentId, id)
                .eq(KbDocumentVersion::getVersionNo, versionNo));
        if (v == null) {
            throw BusinessException.notFound("文档版本");
        }
        SysUser u = v.getCreatedBy() == null ? null : userMapper.selectById(v.getCreatedBy());
        return new VersionDetailView(v.getId(), doc.getId(), v.getVersionNo(), v.getContent(),
                v.getContentHash(), v.getSizeBytes() == null ? 0 : v.getSizeBytes(), v.getChangeNote(),
                v.getCreatedBy(), u == null ? null : u.getDisplayName(), v.getCreatedAt());
    }

    // ==================================================================
    // 创建 / 编辑 / 删除
    // ==================================================================

    @Transactional
    public DocumentDetailView createOnline(AuthenticatedUser user, long spaceId, DocumentCreateRequest req) {
        KbSpace space = accessService.requireWritable(user, spaceId);
        String content = requireContent(req.content());
        KbDocument doc = createDocument(user, space, req.title().trim(), content, null,
                DocumentSource.EDITOR, null, null, req.changeNote(), req.effectiveFrom(), req.effectiveTo());
        audit("KB_DOCUMENT_CREATE", doc.getId(), "spaceId", spaceId, "title", doc.getTitle());
        return get(user, doc.getId());
    }

    @Transactional
    public DocumentDetailView update(AuthenticatedUser user, long id, DocumentUpdateRequest req) {
        KbDocument doc = requireWritableDocument(user, id);
        String content = requireContent(req.content());
        KbDocument locked = documentMapper.selectByIdForUpdate(id);
        if (locked == null || locked.getStatus() != DocumentStatus.ACTIVE) {
            throw BusinessException.notFound("文档");
        }
        if (!locked.getVersion().equals(req.version())) {
            throw BusinessException.conflict("文档已被其他人修改，请刷新后重试");
        }
        KbDocumentVersion version = insertVersion(user, locked, content, req.changeNote(),
                req.effectiveFrom(), req.effectiveTo());
        chunkMapper.markStaleByDocument(id);
        insertChunks(locked, version, content);
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, id)
                .eq(KbDocument::getVersion, req.version())
                .set(KbDocument::getTitle, req.title().trim())
                .set(KbDocument::getCurrentVersionId, version.getId())
                .set(KbDocument::getContentHash, version.getContentHash())
                .set(KbDocument::getProcessStatus, ProcessStatus.INDEXED)
                .set(KbDocument::getProcessError, null)
                .set(KbDocument::getEffectiveFrom, req.effectiveFrom())
                .set(KbDocument::getEffectiveTo, req.effectiveTo())
                .set(KbDocument::getUpdatedBy, user.userId())
                .set(KbDocument::getUpdatedAt, LocalDateTime.now(clock))
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw BusinessException.conflict("文档已被其他人修改，请刷新后重试");
        }
        audit("KB_DOCUMENT_UPDATE", id, "spaceId", locked.getSpaceId(), "versionNo", version.getVersionNo());
        return get(user, id);
    }

    @Transactional
    public void delete(AuthenticatedUser user, long id) {
        KbDocument doc = requireWritableDocument(user, id);
        chunkMapper.markStaleByDocument(id);
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, id)
                .eq(KbDocument::getVersion, doc.getVersion())
                .set(KbDocument::getStatus, DocumentStatus.DELETED)
                .set(KbDocument::getDeletedAt, LocalDateTime.now(clock))
                .set(KbDocument::getDeletedBy, user.userId())
                .set(KbDocument::getUpdatedBy, user.userId())
                .set(KbDocument::getUpdatedAt, LocalDateTime.now(clock))
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw BusinessException.conflict("文档已被其他人修改，请刷新后重试");
        }
        audit("KB_DOCUMENT_DELETE", id, "spaceId", doc.getSpaceId(), "title", doc.getTitle());
    }

    @Transactional
    public DocumentDetailView reprocess(AuthenticatedUser user, long id) {
        KbDocument doc = requireWritableDocument(user, id);
        if (doc.getCurrentVersionId() == null) {
            throw BusinessException.rule("文档没有可重处理的版本");
        }
        KbDocumentVersion version = versionMapper.selectById(doc.getCurrentVersionId());
        if (version == null) {
            throw BusinessException.rule("文档版本不存在");
        }
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getVersionId, version.getId()));
        insertChunks(doc, version, version.getContent());
        documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, id)
                .set(KbDocument::getProcessStatus, ProcessStatus.INDEXED)
                .set(KbDocument::getProcessError, null)
                .set(KbDocument::getUpdatedBy, user.userId())
                .set(KbDocument::getUpdatedAt, LocalDateTime.now(clock)));
        audit("KB_DOCUMENT_REPROCESS", id, "spaceId", doc.getSpaceId());
        return get(user, id);
    }

    // ==================================================================
    // 本地语料导入（幂等）
    // ==================================================================

    /**
     * 导入/更新一个本地 Markdown 文件。sourcePath 在空间内唯一；内容哈希相同则跳过。
     * 返回 changed=false 表示文件没有变化。
     */
    @Transactional
    public ImportResult importMarkdown(AuthenticatedUser user, long spaceId, String sourcePath,
                                       String title, String content, String originalFilename,
                                       String changeNote) {
        accessService.requireWritable(user, spaceId);
        content = requireContent(content);
        String safePath = truncate(sourcePath, 500);
        String safeTitle = truncate(title == null || title.isBlank() ? "未命名文档" : title.trim(), 255);
        String hash = ContentHash.sha256(content);
        KbDocument existing = documentMapper.selectOne(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getSpaceId, spaceId)
                .eq(KbDocument::getSourcePath, safePath));
        if (existing == null) {
            KbDocument doc = createDocument(user, accessService.requireSpace(spaceId), safeTitle, content,
                    originalFilename, DocumentSource.IMPORT, null, safePath,
                    changeNote == null ? "本地语料导入" : changeNote, null, null);
            audit("KB_DOCUMENT_IMPORT", doc.getId(), "spaceId", spaceId, "sourcePath", safePath, "created", true);
            return new ImportResult(true, doc.getId(), doc.getTitle());
        }

        KbDocument locked = documentMapper.selectByIdForUpdate(existing.getId());
        if (locked == null) {
            throw BusinessException.notFound("文档");
        }
        if (locked.getStatus() == DocumentStatus.ACTIVE && hash.equals(locked.getContentHash())) {
            return new ImportResult(false, locked.getId(), locked.getTitle());
        }
        KbDocumentVersion version = insertVersion(user, locked, content,
                changeNote == null ? "本地语料导入" : changeNote, null, null);
        chunkMapper.markStaleByDocument(locked.getId());
        insertChunks(locked, version, content);
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, locked.getId())
                .eq(KbDocument::getVersion, locked.getVersion())
                .set(KbDocument::getTitle, safeTitle)
                .set(KbDocument::getOriginalFilename, originalFilename)
                .set(KbDocument::getSourceType, DocumentSource.IMPORT)
                .set(KbDocument::getCurrentVersionId, version.getId())
                .set(KbDocument::getContentHash, version.getContentHash())
                .set(KbDocument::getStatus, DocumentStatus.ACTIVE)
                .set(KbDocument::getProcessStatus, ProcessStatus.INDEXED)
                .set(KbDocument::getProcessError, null)
                .set(KbDocument::getDeletedAt, null)
                .set(KbDocument::getDeletedBy, null)
                .set(KbDocument::getUpdatedBy, user.userId())
                .set(KbDocument::getUpdatedAt, LocalDateTime.now(clock))
                .setSql("version = version + 1"));
        if (updated != 1) {
            throw BusinessException.conflict("文档已被其他人修改，请刷新后重试");
        }
        audit("KB_DOCUMENT_IMPORT", locked.getId(), "spaceId", spaceId, "sourcePath", safePath, "updated", true);
        return new ImportResult(true, locked.getId(), safeTitle);
    }

    public record ImportResult(boolean changed, long documentId, String title) {
    }

    // ==================================================================
    // 批量上传
    // ==================================================================

    public UploadBatchView upload(AuthenticatedUser user, long spaceId, List<MultipartFile> files) {
        KbSpace space = accessService.requireWritable(user, spaceId);
        if (files == null || files.isEmpty()) {
            throw BusinessException.badRequest("请选择要上传的 Markdown 文件");
        }
        if (files.size() > properties.maxFilesPerBatch()) {
            throw BusinessException.badRequest("单次最多上传 " + properties.maxFilesPerBatch() + " 个文件");
        }

        KbUploadBatch batch = new KbUploadBatch();
        batch.setSpaceId(spaceId);
        batch.setUploaderId(user.userId());
        batch.setTotalCount(files.size());
        batch.setSuccessCount(0);
        batch.setFailedCount(0);
        batch.setStatus(BatchStatus.PROCESSING);
        batchMapper.insert(batch);

        List<UploadItemResult> items = new ArrayList<>();
        int success = 0;
        int failed = 0;
        for (MultipartFile file : files) {
            String filename = safeFilename(file.getOriginalFilename());
            try {
                UploadItemResult result = tx.execute(status -> importFile(user, space, batch.getId(), file, filename));
                items.add(result);
                success++;
            } catch (BusinessException ex) {
                failed++;
                Long failedId = recordFailure(user, spaceId, batch.getId(), filename, ex.getMessage());
                items.add(new UploadItemResult(filename, false, failedId, null, ex.getMessage()));
            } catch (RuntimeException ex) {
                failed++;
                log.error("Markdown 处理失败: spaceId={}, file={}", spaceId, filename, ex);
                Long failedId = recordFailure(user, spaceId, batch.getId(), filename, "文件处理失败");
                items.add(new UploadItemResult(filename, false, failedId, null, "文件处理失败"));
            }
        }

        batch.setSuccessCount(success);
        batch.setFailedCount(failed);
        batch.setStatus(failed == 0 ? BatchStatus.COMPLETED
                : success == 0 ? BatchStatus.FAILED : BatchStatus.PARTIAL);
        batchMapper.updateById(batch);
        audit("KB_BATCH_UPLOAD", batch.getId(), "spaceId", spaceId, "total", files.size(),
                "success", success, "failed", failed);
        return new UploadBatchView(batch.getId(), spaceId, files.size(), success, failed, batch.getStatus(), items);
    }

    /** 批次详情：从文档记录还原每个文件的处理结果，便于上传后再次查看失败文件。 */
    public UploadBatchView batch(AuthenticatedUser user, long batchId) {
        KbUploadBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw BusinessException.notFound("上传批次");
        }
        accessService.requireReadable(user, batch.getSpaceId());
        List<KbDocument> docs = documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getBatchId, batchId)
                .orderByAsc(KbDocument::getId));
        List<UploadItemResult> items = docs.stream()
                .map(d -> new UploadItemResult(d.getOriginalFilename(), d.getProcessStatus() != ProcessStatus.FAILED,
                        d.getId(), d.getTitle(), d.getProcessError()))
                .toList();
        return new UploadBatchView(batch.getId(), batch.getSpaceId(), batch.getTotalCount(),
                batch.getSuccessCount(), batch.getFailedCount(), batch.getStatus(), items);
    }

    private UploadItemResult importFile(AuthenticatedUser user, KbSpace space, Long batchId,
                                        MultipartFile file, String filename) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.KB_DOCUMENT_EMPTY, "文件为空");
        }
        if (filename == null || !DocumentTextExtractor.isSupported(filename)) {
            throw new BusinessException(ErrorCode.KB_DOCUMENT_INVALID_FILE,
                    "只支持 .md / .txt / .docx / .pdf 文件");
        }
        if (file.getSize() > properties.maxFileBytes()) {
            throw new BusinessException(ErrorCode.KB_DOCUMENT_INVALID_FILE, "文件超过大小限制");
        }
        String content;
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.KB_DOCUMENT_INVALID_FILE, "文件读取失败", ex);
        }
        // .md/.txt 直接用原文；.docx/.pdf 先转成纯文本，之后走同一套分块与索引
        content = extractor.extract(filename, bytes).text();
        content = requireContent(content);
        String parsedTitle = MarkdownParser.parse(content).title();
        String title = parsedTitle == null || parsedTitle.isBlank()
                ? DocumentTextExtractor.baseName(filename) : parsedTitle;
        KbDocument doc = createDocument(user, space, truncate(title, 255), content, filename,
                DocumentSource.UPLOAD, batchId, null, "批量上传", null, null);
        audit("KB_DOCUMENT_UPLOAD", doc.getId(), "spaceId", space.getId(), "filename", filename);
        return new UploadItemResult(filename, true, doc.getId(), doc.getTitle(), null);
    }

    private Long recordFailure(AuthenticatedUser user, long spaceId, long batchId, String filename, String error) {
        try {
            return tx.execute(status -> {
                KbDocument doc = new KbDocument();
                doc.setSpaceId(spaceId);
                doc.setBatchId(batchId);
                doc.setTitle(filename == null ? "未命名文档" : truncate(filename, 255));
                doc.setOriginalFilename(filename);
                doc.setSourceType(DocumentSource.UPLOAD);
                doc.setStatus(DocumentStatus.ACTIVE);
                doc.setProcessStatus(ProcessStatus.FAILED);
                doc.setProcessError(truncate(error == null ? "处理失败" : error, 1000));
                doc.setUploadedBy(user.userId());
                doc.setVersion(0);
                documentMapper.insert(doc);
                return doc.getId();
            });
        } catch (RuntimeException ex) {
            log.error("记录上传失败文档时出错: spaceId={}, file={}", spaceId, filename, ex);
            return null;
        }
    }

    // ==================================================================
    // 内部：创建版本与块
    // ==================================================================

    private KbDocument createDocument(AuthenticatedUser user, KbSpace space, String title, String content,
                                      String originalFilename, DocumentSource sourceType, Long batchId,
                                      String sourcePath, String changeNote,
                                      LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
        KbDocument doc = new KbDocument();
        doc.setSpaceId(space.getId());
        doc.setBatchId(batchId);
        doc.setTitle(title);
        doc.setOriginalFilename(originalFilename);
        doc.setSourceType(sourceType);
        doc.setSourcePath(sourcePath);
        doc.setStatus(DocumentStatus.ACTIVE);
        doc.setProcessStatus(ProcessStatus.PROCESSING);
        doc.setContentHash(ContentHash.sha256(content));
        doc.setEffectiveFrom(effectiveFrom);
        doc.setEffectiveTo(effectiveTo);
        doc.setUploadedBy(user.userId());
        doc.setVersion(0);
        documentMapper.insert(doc);

        KbDocumentVersion version = insertVersion(user, doc, content, changeNote, effectiveFrom, effectiveTo);
        insertChunks(doc, version, content);
        documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, doc.getId())
                .set(KbDocument::getCurrentVersionId, version.getId())
                .set(KbDocument::getProcessStatus, ProcessStatus.INDEXED)
                .set(KbDocument::getProcessError, null));
        doc.setCurrentVersionId(version.getId());
        doc.setProcessStatus(ProcessStatus.INDEXED);
        return doc;
    }

    private KbDocumentVersion insertVersion(AuthenticatedUser user, KbDocument doc, String content,
                                            String changeNote, LocalDateTime from, LocalDateTime to) {
        int no = versionMapper.nextVersionNo(doc.getId());
        KbDocumentVersion version = new KbDocumentVersion();
        version.setDocumentId(doc.getId());
        version.setVersionNo(no);
        version.setContent(content);
        version.setContentHash(ContentHash.sha256(content));
        version.setSizeBytes(content.getBytes(StandardCharsets.UTF_8).length);
        version.setChangeNote(changeNote);
        version.setCreatedBy(user.userId());
        versionMapper.insert(version);
        return version;
    }

    private void insertChunks(KbDocument doc, KbDocumentVersion version, String content) {
        List<MarkdownChunker.ChunkDraft> drafts = chunker.chunk(MarkdownParser.parse(content).body());
        int index = 0;
        for (MarkdownChunker.ChunkDraft draft : drafts) {
            KbChunk chunk = new KbChunk();
            chunk.setDocumentId(doc.getId());
            chunk.setVersionId(version.getId());
            chunk.setSpaceId(doc.getSpaceId());
            chunk.setDocumentTitle(doc.getTitle());
            chunk.setChunkIndex(index++);
            chunk.setHeadingPath(draft.headingPath());
            chunk.setContent(draft.content());
            chunk.setCharCount(draft.charCount());
            chunk.setTokenEstimate(draft.tokenEstimate());
            chunk.setStatus(ChunkStatus.ACTIVE);
            chunk.setEmbeddingStatus(EmbeddingStatus.NONE);
            chunkMapper.insert(chunk);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private KbDocument requireVisibleDocument(AuthenticatedUser user, long id) {
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null || doc.getStatus() != DocumentStatus.ACTIVE) {
            throw BusinessException.notFound("文档");
        }
        accessService.requireReadable(user, doc.getSpaceId());
        return doc;
    }

    private KbDocument requireWritableDocument(AuthenticatedUser user, long id) {
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null || doc.getStatus() != DocumentStatus.ACTIVE) {
            throw BusinessException.notFound("文档");
        }
        accessService.requireWritable(user, doc.getSpaceId());
        return doc;
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.KB_DOCUMENT_EMPTY);
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > properties.maxFileBytes()) {
            throw new BusinessException(ErrorCode.KB_DOCUMENT_INVALID_FILE, "内容超过大小限制");
        }
        return content;
    }

    private static String safeFilename(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            String name = Path.of(raw).getFileName().toString().trim();
            return name.isEmpty() ? null : truncate(name, 255);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Map<Long, KbDocumentVersion> versionsOf(Collection<Long> ids) {
        Set<Long> clean = clean(ids);
        Map<Long, KbDocumentVersion> result = new HashMap<>();
        if (!clean.isEmpty()) {
            versionMapper.selectBatchIds(clean).forEach(v -> result.put(v.getId(), v));
        }
        return result;
    }

    private Map<Long, String> namesOfUsers(Collection<Long> ids) {
        Set<Long> clean = clean(ids);
        Map<Long, String> result = new HashMap<>();
        if (!clean.isEmpty()) {
            userMapper.selectBatchIds(clean).forEach(u -> result.put(u.getId(), u.getDisplayName()));
        }
        return result;
    }

    private static Set<Long> clean(Collection<Long> ids) {
        Set<Long> set = new HashSet<>();
        ids.forEach(id -> {
            if (id != null && id > 0) {
                set.add(id);
            }
        });
        return set;
    }

    private DocumentSummaryView summary(KbDocument d, KbDocumentVersion v, String uploader, long chunkCount) {
        return new DocumentSummaryView(d.getId(), d.getSpaceId(), d.getBatchId(), d.getTitle(),
                d.getOriginalFilename(), d.getSourceType(), d.getSourcePath(), d.getStatus(), d.getProcessStatus(),
                d.getProcessError(), d.getContentHash(), d.getCurrentVersionId(),
                v == null ? null : v.getVersionNo(), v == null ? null : v.getSizeBytes(), chunkCount,
                d.getUploadedBy(), uploader, d.getVersion() == null ? 0 : d.getVersion(),
                d.getCreatedAt(), d.getUpdatedAt());
    }

    private DocumentDetailView detail(KbDocument d, KbDocumentVersion v, String uploader, long chunkCount) {
        return new DocumentDetailView(d.getId(), d.getSpaceId(), d.getBatchId(), d.getTitle(),
                d.getOriginalFilename(), d.getSourceType(), d.getSourcePath(), d.getStatus(), d.getProcessStatus(),
                d.getProcessError(), d.getContentHash(), d.getCurrentVersionId(),
                v == null ? null : v.getVersionNo(), v == null ? null : v.getSizeBytes(), chunkCount,
                d.getUploadedBy(), uploader, d.getVersion() == null ? 0 : d.getVersion(),
                v == null ? null : v.getContent(), d.getEffectiveFrom(), d.getEffectiveTo(),
                d.getCreatedAt(), d.getUpdatedAt());
    }

    private void audit(String action, Long targetId, Object... kv) {
        AuditEvent.Builder b = AuditEvent.builder(MODULE, action).target("KB_DOCUMENT", targetId);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.detail(String.valueOf(kv[i]), kv[i + 1]);
        }
        auditService.record(b.build());
    }
}
