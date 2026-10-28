package com.campus.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 文档版本（kb_document_version）。content 为 Markdown 原文，不可变。 */
@Getter
@Setter
@TableName("kb_document_version")
public class KbDocumentVersion extends BaseEntity {

    private Long documentId;
    private Integer versionNo;
    private String content;
    private String contentHash;
    private Integer sizeBytes;
    private String changeNote;
    private Long createdBy;
}
