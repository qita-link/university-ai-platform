package com.campus.platform.evaluation.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.AuditableEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 学生评教（teaching_evaluation）。
 * <p>
 * 同一学生对同一课程同一教师只能提交一次（数据库唯一约束兜底）；教师侧只看到聚合结果与匿名留言，
 * 不返回学生身份，避免评教影响成绩。
 */
@Getter
@Setter
@TableName("teaching_evaluation")
public class TeachingEvaluation extends AuditableEntity {

    private Long semesterId;
    private Long courseId;
    private Long teacherId;
    private Long studentId;
    private Integer scoreTeaching;
    private Integer scoreContent;
    private Integer scoreInteraction;
    private Integer scoreWorkload;
    private BigDecimal totalScore;
    private String comment;
    private Boolean anonymous;
}
