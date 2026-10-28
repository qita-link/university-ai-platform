package com.campus.platform.knowledge.entity;

/** 向量化状态，阶段四接入 Qdrant/Embedding 后使用。 */
public enum EmbeddingStatus {
    NONE,
    PENDING,
    DONE,
    FAILED
}
