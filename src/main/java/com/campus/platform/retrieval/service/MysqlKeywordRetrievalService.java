package com.campus.platform.retrieval.service;

import com.campus.platform.common.util.SearchTerms;
import com.campus.platform.knowledge.entity.KbSpace;
import com.campus.platform.knowledge.mapper.KbChunkMapper;
import com.campus.platform.knowledge.mapper.KbSpaceMapper;
import com.campus.platform.knowledge.service.ContentHash;
import com.campus.platform.knowledge.service.KnowledgeSpaceService;
import com.campus.platform.retrieval.KnowledgeRetrievalService;
import com.campus.platform.retrieval.RetrievalProperties;
import com.campus.platform.retrieval.dto.KnowledgeSearchHit;
import com.campus.platform.retrieval.dto.KnowledgeSearchRequest;
import com.campus.platform.retrieval.dto.KnowledgeSearchResult;
import com.campus.platform.retrieval.entity.SearchStrategy;
import com.campus.platform.retrieval.mapper.ChunkSearchRow;
import com.campus.platform.security.AuthenticatedUser;
import com.campus.platform.security.Permissions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MySQL ngram 关键词检索。
 * <p>
 * 1) 先用 KnowledgeSpaceService 计算当前用户可读空间，权限过滤发生在检索之前；
 * 2) SQL 内用标题 FULLTEXT * 4 加权 + 正文/章节 FULLTEXT；
 * 3) 应用层按内容哈希去重、过滤低分、截断 snippet；
 * 4) 没有足够结果时明确返回 insufficientEvidence，不伪造命中。
 */
@Service
public class MysqlKeywordRetrievalService implements KnowledgeRetrievalService {

    private static final SearchStrategy STRATEGY = SearchStrategy.KEYWORD;

    private final KbChunkMapper chunkMapper;
    private final KbSpaceMapper spaceMapper;
    private final KnowledgeSpaceService spaceService;
    private final RetrievalProperties properties;

    public MysqlKeywordRetrievalService(KbChunkMapper chunkMapper, KbSpaceMapper spaceMapper,
                                        KnowledgeSpaceService spaceService, RetrievalProperties properties) {
        this.chunkMapper = chunkMapper;
        this.spaceMapper = spaceMapper;
        this.spaceService = spaceService;
        this.properties = properties;
    }

    @Override
    public KnowledgeSearchResult search(AuthenticatedUser user, KnowledgeSearchRequest request) {
        Permissions.require(user, Permissions.KB_SPACE_READ);
        String query = toBooleanQuery(request.query());
        if (query.isBlank()) {
            return empty(request.query(), 0);
        }
        int topK = request.topK() == null ? properties.defaultTopK()
                : Math.min(Math.max(1, request.topK()), properties.maxTopK());
        double minScore = request.minScore() == null ? properties.minScore() : Math.max(0, request.minScore());

        List<Long> allowed = allowedSpaces(user, request.spaceIds());
        if (allowed.isEmpty()) {
            return empty(request.query(), 0);
        }
        int candidateLimit = Math.min(100, Math.max(topK, topK * properties.candidateFactor()));
        List<ChunkSearchRow> rows = chunkMapper.search(query, allowed, candidateLimit);

        // 按内容哈希去重；行已按 score DESC 排序，保留首次出现的高分块。
        Map<String, ChunkSearchRow> unique = new LinkedHashMap<>();
        for (ChunkSearchRow row : rows) {
            double score = row.getScore() == null ? 0 : row.getScore();
            if (score <= 0 || score < minScore) {
                continue;
            }
            unique.putIfAbsent(ContentHash.sha256(row.getContent() == null ? "" : row.getContent()), row);
        }
        List<ChunkSearchRow> selected = new ArrayList<>(unique.values());
        if (selected.size() > topK) {
            selected = selected.subList(0, topK);
        }
        if (selected.isEmpty()) {
            return empty(request.query(), rows.size());
        }

        Map<Long, String> spaceNames = new HashMap<>();
        Set<Long> ids = selected.stream().map(ChunkSearchRow::getSpaceId).collect(java.util.stream.Collectors.toSet());
        if (!ids.isEmpty()) {
            spaceMapper.selectBatchIds(ids).forEach((KbSpace s) -> spaceNames.put(s.getId(), s.getName()));
        }

        List<KnowledgeSearchHit> hits = new ArrayList<>();
        int rank = 1;
        double maxScore = 0;
        for (ChunkSearchRow row : selected) {
            maxScore = Math.max(maxScore, row.getScore() == null ? 0 : row.getScore());
            hits.add(new KnowledgeSearchHit(row.getId(), row.getDocumentId(), row.getVersionId(),
                    row.getSpaceId(), spaceNames.get(row.getSpaceId()), row.getDocumentTitle(),
                    row.getHeadingPath(), snippet(row.getContent(), properties.snippetChars()),
                    row.getContent(), row.getScore() == null ? 0 : row.getScore(), rank++));
        }
        return new KnowledgeSearchResult(request.query(), STRATEGY, rows.size(), hits.size(), false, maxScore, hits);
    }

    private List<Long> allowedSpaces(AuthenticatedUser user, List<Long> requested) {
        List<Long> allowed = spaceService.accessibleSpaceIds(user);
        if (requested == null || requested.isEmpty()) {
            return allowed;
        }
        Set<Long> requestedSet = Set.copyOf(requested);
        return allowed.stream().filter(requestedSet::contains).toList();
    }

    private static KnowledgeSearchResult empty(String query, int candidates) {
        return new KnowledgeSearchResult(query, STRATEGY, candidates, 0, true, 0, List.of());
    }

    /**
     * 把自然语言查询转换为 MySQL BOOLEAN MODE 查询串。
     * <p>
     * 具体规则见 {@link SearchTerms}；知识库与论坛共用同一套中文拆词逻辑。
     */
    static String toBooleanQuery(String raw) {
        return SearchTerms.toBooleanQuery(raw);
    }

    private static String snippet(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "…";
    }
}
