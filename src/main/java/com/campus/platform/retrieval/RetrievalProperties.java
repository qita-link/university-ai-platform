package com.campus.platform.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索参数（前缀 app.retrieval）。
 *
 * @param defaultTopK       默认返回条数
 * @param maxTopK           单次最大返回条数
 * @param minScore          最低相关性分数
 * @param candidateFactor   候选数 = topK * factor
 * @param snippetChars      snippet 最大字符数
 */
@ConfigurationProperties(prefix = "app.retrieval")
public record RetrievalProperties(
        Integer defaultTopK,
        Integer maxTopK,
        Double minScore,
        Integer candidateFactor,
        Integer snippetChars) {

    public RetrievalProperties {
        defaultTopK = defaultTopK == null || defaultTopK < 1 ? 5 : defaultTopK;
        maxTopK = maxTopK == null || maxTopK < 1 ? 20 : maxTopK;
        if (defaultTopK > maxTopK) {
            defaultTopK = maxTopK;
        }
        minScore = minScore == null || minScore < 0 ? 0.0 : minScore;
        candidateFactor = candidateFactor == null || candidateFactor < 1 ? 4 : candidateFactor;
        snippetChars = snippetChars == null || snippetChars < 80 ? 300 : snippetChars;
    }
}
