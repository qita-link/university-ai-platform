package com.campus.platform.retrieval.entity;

/** 检索策略；阶段四先实现 KEYWORD，VECTOR/HYBRID 在接入 Qdrant 后实现。 */
public enum SearchStrategy {
    KEYWORD,
    VECTOR,
    HYBRID
}
