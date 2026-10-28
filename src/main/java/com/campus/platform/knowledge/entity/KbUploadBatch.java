package com.campus.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 批量上传批次（kb_upload_batch）。 */
@Getter
@Setter
@TableName("kb_upload_batch")
public class KbUploadBatch extends BaseEntity {

    private Long spaceId;
    private Long uploaderId;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private BatchStatus status;
}
