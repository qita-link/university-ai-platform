package com.campus.platform.scheduling.entity;

/**
 * 排课版本状态。每学期最多一个 PUBLISHED（由 sch_version.published_key 生成列保证）。
 */
public enum VersionStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED;

    public boolean editable() {
        return this == DRAFT;
    }
}
