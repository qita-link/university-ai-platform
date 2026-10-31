package com.campus.platform.feedback.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 投诉与建议（campus_feedback）。
 * <p>
 * 提交人始终落库用于追溯；{@code anonymous=TRUE} 时接口对其他角色隐藏身份。
 */
@Getter
@Setter
@TableName("campus_feedback")
public class CampusFeedback extends AuditableEntity {

    private FeedbackCategory category;
    private String title;
    private String content;
    private Boolean anonymous;
    private Long studentId;
    private FeedbackStatus status;
    private Long handlerId;
    private String reply;
    private LocalDateTime repliedAt;

    @Version
    private Integer version;
}
