package com.campus.platform.grade.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 校务服务：绩点换算区间的边界判定（左闭右开，100 分含在最后一档）。 */
class GradeRuleTest {

    private static GradeRule rule(String min, String max, String point, String level) {
        GradeRule r = new GradeRule();
        r.setMinScore(new BigDecimal(min));
        r.setMaxScore(new BigDecimal(max));
        r.setGradePoint(new BigDecimal(point));
        r.setGradeLevel(level);
        return r;
    }

    @Test
    void ninetyIsFourPointZeroAndJustBelowIsThreePointSeven() {
        GradeRule a = rule("90", "100", "4.00", "A");
        GradeRule am = rule("85", "90", "3.70", "A-");

        assertThat(a.covers(new BigDecimal("90"))).isTrue();
        assertThat(a.covers(new BigDecimal("100"))).isTrue();
        assertThat(a.covers(new BigDecimal("89.99"))).isFalse();
        assertThat(am.covers(new BigDecimal("89.99"))).isTrue();
        assertThat(am.covers(new BigDecimal("90"))).isFalse();
    }

    @Test
    void zeroIsCoveredAndNegativeIsNot() {
        GradeRule f = rule("0", "60", "0.00", "F");

        assertThat(f.covers(BigDecimal.ZERO)).isTrue();
        assertThat(f.covers(new BigDecimal("59.99"))).isTrue();
        assertThat(f.covers(new BigDecimal("-1"))).isFalse();
        assertThat(f.covers(null)).isFalse();
    }

    @Test
    void upperBoundOfMiddleRangeIsExclusive() {
        GradeRule b = rule("78", "82", "3.00", "B");

        assertThat(b.covers(new BigDecimal("78"))).isTrue();
        assertThat(b.covers(new BigDecimal("81.99"))).isTrue();
        assertThat(b.covers(new BigDecimal("82"))).isFalse();
    }
}
