package com.campus.platform.grade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campus.platform.common.persistence.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 绩点换算规则（grade_rule）：分数区间 → 绩点与等级。
 * <p>
 * 区间按左闭右开解释，最后一段包含 100 分；查询时按 min_score 从高到低命中第一条。
 */
@Getter
@Setter
@TableName("grade_rule")
public class GradeRule extends BaseEntity {

    private String name;
    private BigDecimal minScore;
    private BigDecimal maxScore;
    private BigDecimal gradePoint;
    private String gradeLevel;
    private Integer sortOrder;

    /** 该分数是否落在本区间。 */
    public boolean covers(BigDecimal score) {
        if (score == null) {
            return false;
        }
        boolean upperOk = score.compareTo(maxScore) < 0
                || (maxScore.compareTo(BigDecimal.valueOf(100)) == 0 && score.compareTo(maxScore) <= 0);
        return score.compareTo(minScore) >= 0 && upperOk;
    }
}
