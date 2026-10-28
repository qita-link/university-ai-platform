package com.campus.platform.ai.tool;

import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具参数读取与校验。
 * <p>
 * 参数来自模型或调用方，一律视为不可信输入：缺参、类型不符、超长、枚举越界都转为
 * {@code AI_TOOL_ARGUMENT_INVALID}（400），并在提示里明确指出是哪个参数，便于模型自我修正。
 * 只做“形状”校验，业务规则仍由 Service 判断。
 */
public final class AiToolArguments {

    private static final int SUMMARY_VALUE_CHARS = 40;

    private final Map<String, Object> values;

    private AiToolArguments(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    public static AiToolArguments of(Map<String, Object> raw) {
        return new AiToolArguments(raw);
    }

    public Map<String, Object> asMap() {
        return values;
    }

    // ------------------------------------------------------------------ 字符串

    public String requireString(String name, int maxLength) {
        String value = optionalString(name, maxLength);
        if (value == null) {
            throw invalid(name, "不能为空");
        }
        return value;
    }

    public String optionalString(String name, int maxLength) {
        Object raw = values.get(name);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return null;
        }
        if (maxLength > 0 && value.length() > maxLength) {
            throw invalid(name, "长度不能超过 " + maxLength + " 个字符");
        }
        return value;
    }

    /** 枚举参数：大小写不敏感，返回大写规范值。 */
    public String requireEnum(String name, List<String> allowed) {
        String value = requireString(name, 32).toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw invalid(name, "只能是 " + String.join("/", allowed));
        }
        return value;
    }

    // ------------------------------------------------------------------ 数值

    public Long requireLong(String name) {
        Long value = optionalLong(name);
        if (value == null) {
            throw invalid(name, "不能为空");
        }
        return value;
    }

    public Long optionalLong(String name) {
        Object raw = values.get(name);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return null;
        }
        long value;
        try {
            value = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            throw invalid(name, "必须是整数");
        }
        if (value <= 0) {
            throw invalid(name, "必须是正整数");
        }
        return value;
    }

    public Integer requireInt(String name, int min, int max) {        Integer value = optionalInt(name, null, min, max);
        if (value == null) {
            throw invalid(name, "不能为空");
        }
        return value;
    }

    public Integer optionalInt(String name, Integer defaultValue, int min, int max) {        Object raw = values.get(name);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return defaultValue;
        }
        int value;
        try {
            value = raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            throw invalid(name, "必须是整数");
        }
        if (value < min || value > max) {
            throw invalid(name, "必须在 " + min + "~" + max + " 之间");
        }
        return value;
    }

    /** 小数参数（如学分）：接受数字或数字字符串，并校验区间。 */
    public double requireDouble(String name, double min, double max) {
        Object raw = values.get(name);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            throw invalid(name, "不能为空");
        }
        double value;
        try {
            value = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            throw invalid(name, "必须是数字");
        }
        if (value < min || value > max) {
            throw invalid(name, "必须在 " + min + "~" + max + " 之间");
        }
        return value;
    }

    /** 整数数组参数（如 spaceIds）：接受数组或单个值，逐项校验为正整数。 */
    public List<Long> optionalLongList(String name, int maxSize) {
        Object raw = values.get(name);
        if (raw == null) {
            return List.of();
        }
        List<?> items = raw instanceof List<?> list ? list : List.of(raw);
        if (items.size() > maxSize) {
            throw invalid(name, "最多 " + maxSize + " 项");
        }
        List<Long> result = new java.util.ArrayList<>();
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            long value;
            try {
                value = item instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(item).trim());
            } catch (NumberFormatException ex) {
                throw invalid(name, "必须全部是整数");
            }
            if (value <= 0) {
                throw invalid(name, "必须全部是正整数");
            }
            result.add(value);
        }
        return result;
    }

    // ------------------------------------------------------------------ 时间

    /**
     * 时间参数：接受 ISO-8601（{@code 2026-10-28T10:00:00}）或 {@code 2026-10-28 10:00:00}，
     * 也接受纯日期（按当天 00:00 处理）。按业务时区解释，不带时区偏移。
     */
    public LocalDateTime requireDateTime(String name) {
        String value = requireString(name, 32);
        return parseDateTime(name, value);
    }

    public LocalDateTime optionalDateTime(String name) {
        String value = optionalString(name, 32);
        return value == null ? null : parseDateTime(name, value);
    }

    private static LocalDateTime parseDateTime(String name, String value) {
        String normalized = value.replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException ignored) {
            // 继续尝试纯日期
        }
        try {
            return LocalDate.parse(normalized).atStartOfDay();
        } catch (DateTimeParseException ex) {
            throw invalid(name, "时间格式应为 2026-10-28T10:00:00");
        }
    }

    // ------------------------------------------------------------------ 摘要

    /** 参数可读摘要（值过长会截断），用于列表展示与审计，不包含敏感字段。 */
    public String summary() {
        StringBuilder b = new StringBuilder();
        Map<String, Object> ordered = new LinkedHashMap<>(values);
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            Object raw = entry.getValue();
            if (raw == null) {
                continue;
            }
            String text = String.valueOf(raw);
            if (text.length() > SUMMARY_VALUE_CHARS) {
                text = text.substring(0, SUMMARY_VALUE_CHARS) + "…";
            }
            if (!b.isEmpty()) {
                b.append(", ");
            }
            b.append(entry.getKey()).append('=').append(text);
        }
        return b.length() > 500 ? b.substring(0, 500) : b.toString();
    }

    private static BusinessException invalid(String name, String reason) {
        return new BusinessException(ErrorCode.AI_TOOL_ARGUMENT_INVALID, "参数 " + name + " " + reason);
    }
}
