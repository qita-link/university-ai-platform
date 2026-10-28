package com.campus.platform.retrieval.mapper;

import lombok.Getter;
import lombok.Setter;

/** 全文检索 SQL 的投影行，score 为标题加权后的分数。 */
@Getter
@Setter
public class ChunkSearchRow {
    private Long id;
    private Long documentId;
    private Long versionId;
    private Long spaceId;
    private String documentTitle;
    private String headingPath;
    private String content;
    private Integer charCount;
    private Integer tokenEstimate;
    private Double score;
}
