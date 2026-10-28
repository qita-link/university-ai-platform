package com.campus.platform.retrieval;

import com.campus.platform.retrieval.dto.KnowledgeSearchRequest;
import com.campus.platform.retrieval.dto.KnowledgeSearchResult;
import com.campus.platform.security.AuthenticatedUser;

/**
 * 统一检索接口。阶段四实现 MySQL ngram 关键词检索；后续可替换/组合 Qdrant 向量检索，
 * 业务层（AI 问答、工具）不需要关心底层实现。
 */
public interface KnowledgeRetrievalService {

    KnowledgeSearchResult search(AuthenticatedUser user, KnowledgeSearchRequest request);
}
