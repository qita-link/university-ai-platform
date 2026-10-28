package com.campus.platform.course.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 课程（course）。
 * <p>
 * {@code selectedCount} 是冗余计数，只由选课服务通过条件更新维护（选课 +1、退课 -1、停开清零），
 * 数据库 CHECK(selected_count <= capacity) 作为兜底，因此缩小容量前必须先校验。
 * {@code version} 只跟踪课程资料修改，选课计数变化不递增它。
 */
@Getter
@Setter
@TableName("course")
public class Course extends AuditableEntity {

    private Long semesterId;
    private String code;
    private String name;
    private BigDecimal credit;
    private String description;
    private Long orgUnitId;
    private Integer capacity;
    private Integer selectedCount;
    private CourseStatus status;

    @Version
    private Integer version;

    public boolean isFull() {
        return capacity != null && selectedCount != null && selectedCount >= capacity;
    }
}
