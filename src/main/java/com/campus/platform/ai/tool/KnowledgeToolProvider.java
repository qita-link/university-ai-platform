package com.campus.platform.ai.tool;

import com.campus.platform.common.api.PageResult;
import com.campus.platform.knowledge.dto.DocumentDetailView;
import com.campus.platform.knowledge.dto.DocumentQuery;
import com.campus.platform.knowledge.dto.DocumentSummaryView;
import com.campus.platform.knowledge.service.KnowledgeDocumentService;
import com.campus.platform.retrieval.KnowledgeRetrievalService;
import com.campus.platform.retrieval.dto.KnowledgeSearchRequest;
import com.campus.platform.retrieval.dto.KnowledgeSearchResult;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.campus.platform.ai.tool.ToolParameter.optional;
import static com.campus.platform.ai.tool.ToolParameter.required;

/**
 * 知识库工具（只读）：检索、列文档、读文档。
 * <p>
 * 全部复用阶段三/四的 Service，可见范围与成员授权由 `KnowledgeAccessService` 判断，
 * 工具不会也不能绕过空间权限。
 */
@Component
public class KnowledgeToolProvider implements AiToolProvider {

    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeDocumentService documentService;

    public KnowledgeToolProvider(KnowledgeRetrievalService retrievalService,
                                 KnowledgeDocumentService documentService) {
        this.retrievalService = retrievalService;
        this.documentService = documentService;
    }

    @Override
    public List<AiTool> tools() {
        return List.of(searchKnowledge(), listDocuments(), getDocument());
    }

    private AiTool searchKnowledge() {
        return SimpleAiTool.readOnly("searchKnowledge", "检索知识库",
                "在用户有权访问的知识库中做关键词检索，返回片段、文档标题与章节，用于回答校园政策/流程类问题。只读。",
                List.of(Permissions.KB_SPACE_READ),
                List.of(required("query", "string", "检索词，中文自然语言即可"),
                        optional("spaceIds", "array", "限定知识库空间 ID 列表；为空表示所有有权空间"),
                        optional("topK", "integer", "返回条数 1~20，默认 5")),
                (user, args) -> {
                    List<Long> spaceIds = args.optionalLongList("spaceIds", 50);
                    int topK = args.optionalInt("topK", 5, 1, 20);
                    KnowledgeSearchResult result = retrievalService.search(user,
                            new KnowledgeSearchRequest(args.requireString("query", 500),
                                    spaceIds.isEmpty() ? null : spaceIds, topK, null));
                    return new ToolResult(result, "检索到 " + result.returnedCount()
                            + " 条片段（候选 " + result.candidateCount() + "）");
                });
    }

    private AiTool listDocuments() {
        return SimpleAiTool.readOnly("listKnowledgeDocuments", "列出知识库文档",
                "分页列出某个知识库空间中的文档（标题、处理状态、版本）。只能看到自己有权访问的空间。只读。",
                List.of(Permissions.KB_SPACE_READ),
                List.of(required("spaceId", "integer", "知识库空间 ID"),
                        optional("keyword", "string", "按标题模糊匹配"),
                        optional("page", "integer", "页码，从 1 开始，默认 1"),
                        optional("size", "integer", "每页条数 1~50，默认 10")),
                (user, args) -> {
                    DocumentQuery query = new DocumentQuery();
                    query.setKeyword(args.optionalString("keyword", 255));
                    query.setPage(args.optionalInt("page", 1, 1, 100000));
                    query.setSize(args.optionalInt("size", 10, 1, 50));
                    PageResult<DocumentSummaryView> page = documentService.list(
                            user, args.requireLong("spaceId"), query);
                    return new ToolResult(page, "返回 " + page.items().size() + " 篇，共 " + page.total() + " 篇");
                });
    }

    private AiTool getDocument() {
        return SimpleAiTool.readOnly("getKnowledgeDocument", "读取知识库文档",
                "读取指定文档的当前版本原文与元信息（标题、章节由正文 Markdown 标题体现）。只能读有权访问的文档。只读。",
                List.of(Permissions.KB_SPACE_READ),
                List.of(required("documentId", "integer", "文档 ID")),
                (user, args) -> {
                    DocumentDetailView document = documentService.get(user, args.requireLong("documentId"));
                    int length = document.content() == null ? 0 : document.content().length();
                    return new ToolResult(document, "读取《" + document.title() + "》，正文 " + length + " 字");
                });
    }
}
