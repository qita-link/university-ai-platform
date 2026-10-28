package com.campus.platform.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库处理参数（前缀 app.knowledge）。
 *
 * @param maxChunkChars      单块最大字符数
 * @param chunkOverlapChars  相邻块重叠字符数
 * @param maxFileBytes       单个 Markdown 文件最大字节数
 * @param maxFilesPerBatch   单次批量上传最大文件数
 */
@ConfigurationProperties(prefix = "app.knowledge")
public record KnowledgeProperties(
        Integer maxChunkChars,
        Integer chunkOverlapChars,
        Long maxFileBytes,
        Integer maxFilesPerBatch) {

    public KnowledgeProperties {
        maxChunkChars = maxChunkChars == null || maxChunkChars < 100 ? 800 : maxChunkChars;
        chunkOverlapChars = chunkOverlapChars == null || chunkOverlapChars < 0
                || chunkOverlapChars >= maxChunkChars ? Math.min(120, maxChunkChars / 4) : chunkOverlapChars;
        maxFileBytes = maxFileBytes == null || maxFileBytes < 1024 ? 2L * 1024 * 1024 : maxFileBytes;
        maxFilesPerBatch = maxFilesPerBatch == null || maxFilesPerBatch < 1 ? 20 : maxFilesPerBatch;
    }
}
