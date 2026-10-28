package com.campus.platform.knowledge.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.common.api.PageResult;
import com.campus.platform.knowledge.dto.ChunkView;
import com.campus.platform.knowledge.dto.DocumentCreateRequest;
import com.campus.platform.knowledge.dto.DocumentDetailView;
import com.campus.platform.knowledge.dto.DocumentQuery;
import com.campus.platform.knowledge.dto.DocumentSummaryView;
import com.campus.platform.knowledge.dto.DocumentUpdateRequest;
import com.campus.platform.knowledge.dto.UploadBatchView;
import com.campus.platform.knowledge.dto.VersionDetailView;
import com.campus.platform.knowledge.dto.VersionView;
import com.campus.platform.knowledge.service.KnowledgeDocumentService;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "知识库文档", description = "Markdown 上传、在线编辑、版本、分块与重处理")
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;

    public KnowledgeDocumentController(KnowledgeDocumentService documentService) {
        this.documentService = documentService;
    }

    @Operation(summary = "文档列表", description = "需要 kb:space:read；支持标题/文件名、处理状态筛选")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @GetMapping("/spaces/{spaceId}/documents")
    public ApiResponse<PageResult<DocumentSummaryView>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                             @PathVariable long spaceId,
                                                             @Valid @ParameterObject DocumentQuery query) {
        return ApiResponse.ok(documentService.list(user, spaceId, query));
    }

    @Operation(summary = "在线新建文档", description = "需要 kb:document:write 且对空间有写权限")
    @PreAuthorize("hasAuthority('kb:document:write')")
    @PostMapping("/spaces/{spaceId}/documents")
    public ApiResponse<DocumentDetailView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable long spaceId,
                                                  @Valid @RequestBody DocumentCreateRequest request) {
        return ApiResponse.ok(documentService.createOnline(user, spaceId, request));
    }

    @Operation(summary = "批量上传 Markdown", description = "multipart 字段名 files；逐文件处理，允许部分成功")
    @PreAuthorize("hasAuthority('kb:document:write')")
    @PostMapping(value = "/spaces/{spaceId}/documents/batch-upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadBatchView> upload(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable long spaceId,
                                               @RequestPart("files") List<MultipartFile> files) {
        return ApiResponse.ok(documentService.upload(user, spaceId, files));
    }

    @Operation(summary = "上传批次详情", description = "包含每个文件成功/失败结果，便于查看失败文件")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @GetMapping("/upload-batches/{batchId}")
    public ApiResponse<UploadBatchView> batch(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable long batchId) {
        return ApiResponse.ok(documentService.batch(user, batchId));
    }

    @Operation(summary = "文档详情", description = "包含当前版本 Markdown 原文")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @GetMapping("/documents/{id}")
    public ApiResponse<DocumentDetailView> get(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable long id) {
        return ApiResponse.ok(documentService.get(user, id));
    }

    @Operation(summary = "修改文档", description = "生成新版本并使旧块 STALE；需要回传 version")
    @PreAuthorize("hasAuthority('kb:document:write')")
    @PutMapping("/documents/{id}")
    public ApiResponse<DocumentDetailView> update(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable long id,
                                                  @Valid @RequestBody DocumentUpdateRequest request) {
        return ApiResponse.ok(documentService.update(user, id, request));
    }

    @Operation(summary = "删除文档", description = "软删除，旧块标记 STALE")
    @PreAuthorize("hasAuthority('kb:document:write')")
    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        documentService.delete(user, id);
        return ApiResponse.ok();
    }

    @Operation(summary = "重新切分/索引", description = "基于当前版本重新生成块，不创建新版本")
    @PreAuthorize("hasAuthority('kb:document:write')")
    @PostMapping("/documents/{id}/reprocess")
    public ApiResponse<DocumentDetailView> reprocess(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @PathVariable long id) {
        return ApiResponse.ok(documentService.reprocess(user, id));
    }

    @Operation(summary = "当前版本文档块")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<List<ChunkView>> chunks(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable long id) {
        return ApiResponse.ok(documentService.chunks(user, id));
    }

    @Operation(summary = "版本列表")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @GetMapping("/documents/{id}/versions")
    public ApiResponse<List<VersionView>> versions(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @PathVariable long id) {
        return ApiResponse.ok(documentService.versions(user, id));
    }

    @Operation(summary = "指定版本详情")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @GetMapping("/documents/{id}/versions/{versionNo}")
    public ApiResponse<VersionDetailView> version(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable long id, @PathVariable int versionNo) {
        return ApiResponse.ok(documentService.version(user, id, versionNo));
    }
}
