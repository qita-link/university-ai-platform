package com.campus.platform.ai.tool;

import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 阶段七：工具参数解析与校验（不可信输入的形状校验）。 */
class AiToolArgumentsTest {

    private static AiToolArguments args(Map<String, Object> raw) {
        return AiToolArguments.of(raw);
    }

    @Test
    void requiredStringRejectsMissingAndBlank() {
        assertThatThrownBy(() -> args(Map.of()).requireString("query", 100))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AI_TOOL_ARGUMENT_INVALID);
                    assertThat(ex.getMessage()).contains("query");
                });
        assertThatThrownBy(() -> args(Map.of("query", "   ")).requireString("query", 100))
                .isInstanceOf(BusinessException.class);
        assertThat(args(Map.of("query", " 报到 ")).requireString("query", 100)).isEqualTo("报到");
    }

    @Test
    void stringLengthIsLimited() {
        assertThatThrownBy(() -> args(Map.of("reason", "x".repeat(11))).requireString("reason", 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("长度");
        assertThat(args(Map.of("optional", "")).optionalString("optional", 10)).isNull();
    }

    @Test
    void numbersAcceptStringsAndRejectInvalid() {
        assertThat(args(Map.of("courseId", "12")).requireLong("courseId")).isEqualTo(12L);
        assertThat(args(Map.of("courseId", 12)).requireLong("courseId")).isEqualTo(12L);
        assertThatThrownBy(() -> args(Map.of("courseId", "abc")).requireLong("courseId"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("整数");
        assertThatThrownBy(() -> args(Map.of("courseId", 0)).requireLong("courseId"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正整数");
        assertThat(args(Map.of()).optionalLong("courseId")).isNull();
    }

    @Test
    void integersAndDoublesAreRangeChecked() {
        assertThat(args(Map.of("size", 20)).optionalInt("size", 10, 1, 50)).isEqualTo(20);
        assertThat(args(Map.of()).optionalInt("size", 10, 1, 50)).isEqualTo(10);
        assertThatThrownBy(() -> args(Map.of("size", 51)).optionalInt("size", 10, 1, 50))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1~50");
        assertThat(args(Map.of("credit", 3.5)).requireDouble("credit", 0, 20)).isEqualTo(3.5);
        assertThat(args(Map.of("credit", "2.5")).requireDouble("credit", 0, 20)).isEqualTo(2.5);
        assertThatThrownBy(() -> args(Map.of("credit", 25)).requireDouble("credit", 0, 20))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void longListAcceptsSingleValueAndArray() {
        assertThat(args(Map.of("spaceIds", 3)).optionalLongList("spaceIds", 5)).containsExactly(3L);
        assertThat(args(Map.of("spaceIds", List.of(1, 2, 3))).optionalLongList("spaceIds", 5))
                .containsExactly(1L, 2L, 3L);
        assertThat(args(Map.of()).optionalLongList("spaceIds", 5)).isEmpty();
        assertThatThrownBy(() -> args(Map.of("spaceIds", List.of(1, 2, 3))).optionalLongList("spaceIds", 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多");
        assertThatThrownBy(() -> args(Map.of("spaceIds", List.of("a"))).optionalLongList("spaceIds", 5))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void dateTimeAcceptsIsoSpaceSeparatorAndDateOnly() {
        assertThat(args(Map.of("startAt", "2026-10-28T10:30:00")).requireDateTime("startAt"))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 10, 30));
        assertThat(args(Map.of("startAt", "2026-10-28 10:30:00")).requireDateTime("startAt"))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 10, 30));
        assertThat(args(Map.of("startAt", "2026-10-28")).requireDateTime("startAt"))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 0, 0));
        assertThatThrownBy(() -> args(Map.of("startAt", "明天上午")).requireDateTime("startAt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("时间格式");
        assertThat(args(Map.of()).optionalDateTime("startAt")).isNull();
    }

    @Test
    void enumIsCaseInsensitiveAndValidated() {
        assertThat(args(Map.of("decision", "approved")).requireEnum("decision",
                List.of("APPROVED", "REJECTED"))).isEqualTo("APPROVED");
        assertThatThrownBy(() -> args(Map.of("decision", "MAYBE")).requireEnum("decision",
                List.of("APPROVED", "REJECTED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("APPROVED/REJECTED");
    }

    @Test
    void summaryTruncatesLongValues() {
        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("courseId", 12);
        raw.put("reason", "x".repeat(100));
        raw.put("empty", null);
        String summary = args(raw).summary();
        assertThat(summary).startsWith("courseId=12, reason=").contains("…").doesNotContain("empty");
        assertThat(summary.length()).isLessThanOrEqualTo(500);
    }
}
