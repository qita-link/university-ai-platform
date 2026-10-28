package com.campus.platform.course.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学期（semester）。
 * <p>
 * 选课窗口 {@code selectionStartAt/selectionEndAt} 与学期起止日期相互独立：选课通常发生在开学前，
 * 因此不要求窗口落在学期日期范围内，只要求窗口自身有序、学期起止有序。
 */
@Getter
@Setter
@TableName("semester")
public class Semester extends AuditableEntity {

    private String code;
    private String name;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDateTime selectionStartAt;
    private LocalDateTime selectionEndAt;
    private SemesterStatus status;

    /** 当前时刻是否处于选课窗口内（不看学期状态）。 */
    public boolean inSelectionWindow(LocalDateTime now) {
        return now != null && selectionStartAt != null && selectionEndAt != null
                && !now.isBefore(selectionStartAt) && !now.isAfter(selectionEndAt);
    }

    /** 当前时刻是否允许选课：学期 ACTIVE 且处于选课窗口内。 */
    public boolean selectionOpen(LocalDateTime now) {
        return status == SemesterStatus.ACTIVE && inSelectionWindow(now);
    }

    /** 日期是否落在学期范围内（含首尾）。 */
    public boolean covers(LocalDate date) {
        return date != null && startDate != null && endDate != null
                && !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
