package com.campus.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 知识库文档（kb_document）。删除为软删除，版本与块保留用于追溯。 */
@Getter
@Setter
@TableName("kb_document")
public class KbDocument extends AuditableEntity {

    private Long spaceId;
    private Long batchId;
    private String title;
    private String originalFilename;
    private DocumentSource sourceType;
    /** 本地导入来源相对路径；普通上传为空 */
    private String sourcePath;
    private Long currentVersionId;
    private DocumentStatus status;
    private ProcessStatus processStatus;
    private String processError;
    private String contentHash;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private Long uploadedBy;
    private LocalDateTime deletedAt;
    private Long deletedBy;

    @Version
    private Integer version;

    public boolean isActive() {
        return status == DocumentStatus.ACTIVE;
    }
}
