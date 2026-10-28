package com.campus.platform.retrieval.controller;

import com.campus.platform.common.api.ApiResponse;
import com.campus.platform.retrieval.KnowledgeRetrievalService;
import com.campus.platform.retrieval.dto.KnowledgeSearchRequest;
import com.campus.platform.retrieval.dto.KnowledgeSearchResult;
import com.campus.platform.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "知识库检索", description = "在当前用户有权访问的知识库内做中文关键词检索")
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeSearchController {

    private final KnowledgeRetrievalService retrievalService;

    public KnowledgeSearchController(KnowledgeRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Operation(summary = "关键词检索", description = "权限过滤先于检索；返回片段、来源、章节、文档/版本/chunk ID 与分数")
    @PreAuthorize("hasAuthority('kb:space:read')")
    @PostMapping("/search")
    public ApiResponse<KnowledgeSearchResult> search(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @Valid @RequestBody KnowledgeSearchRequest request) {
        return ApiResponse.ok(retrievalService.search(user, request));
    }
}
