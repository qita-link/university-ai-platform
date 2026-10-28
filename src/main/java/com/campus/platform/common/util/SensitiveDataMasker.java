package com.campus.platform.common.util;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏工具。用于日志输出、审计详情、AI 工具调用参数摘要。
 * <p>
 * 覆盖：password / secret / token / api-key / authorization / credential 等键值对，
 * Bearer 令牌，JWT 字符串，以及常见 "sk-" 前缀的模型 API Key。
 * 这是兜底措施，业务代码仍应避免把秘密信息放进日志和审计详情。
 */
public final class SensitiveDataMasker {

    public static final String MASK = "****";

    /** 敏感键名（统一转小写并去掉 - _ 后比较） */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "oldpassword", "newpassword", "confirmpassword", "passwordhash",
            "secret", "clientsecret", "jwtsecret", "token", "accesstoken", "refreshtoken", "idtoken",
            "apikey", "authorization", "credential", "credentials", "privatekey", "cookie");

    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(\"?(?:password|passwd|pwd|old_?password|new_?password|secret|client_?secret|"
                    + "token|access_?token|refresh_?token|api[-_]?key|authorization|credentials?|private_?key)\"?"
                    + "\\s*[:=]\\s*\"?)([^\"\\s,&;}]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+");
    private static final Pattern SK_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}");

    private SensitiveDataMasker() {
    }

    /** 对任意文本做脱敏。 */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = KEY_VALUE.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + MASK));
        result = BEARER.matcher(result).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + MASK));
        result = JWT.matcher(result).replaceAll(MASK);
        result = SK_KEY.matcher(result).replaceAll("sk-" + MASK);
        return result;
    }

    /** 判断键名是否敏感。 */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return SENSITIVE_KEYS.contains(normalized);
    }

    /**
     * 对参数 Map 做脱敏（递归处理嵌套 Map），敏感键值替换为掩码，字符串值截断到 maxValueLength。
     * 返回新的有序 Map，不修改入参。
     */
    public static Map<String, Object> maskMap(Map<String, ?> source, int maxValueLength) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        source.forEach((k, v) -> result.put(k, isSensitiveKey(k) ? MASK : maskValue(v, maxValueLength)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object maskValue(Object value, int maxLen) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return maskMap((Map<String, ?>) map, maxLen);
        }
        if (value instanceof Collection<?> col) {
            return col.stream().map(v -> maskValue(v, maxLen)).toList();
        }
        if (value instanceof CharSequence cs) {
            return truncate(mask(cs.toString()), maxLen);
        }
        return value;
    }

    /** 截断字符串，超长部分以 "…(+N)" 标注被截掉的字符数。 */
    public static String truncate(String text, int maxLength) {
        if (text == null || maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…(+" + (text.length() - maxLength) + ")";
    }
}
