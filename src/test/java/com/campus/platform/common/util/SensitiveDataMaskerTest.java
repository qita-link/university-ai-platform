package com.campus.platform.common.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    void masksKeyValuePairsInText() {
        String masked = SensitiveDataMasker.mask("login password=Secret123! user=alice");
        assertThat(masked).doesNotContain("Secret123!").contains("password=****").contains("user=alice");
    }

    @Test
    void masksJsonStyleFields() {
        String masked = SensitiveDataMasker.mask("{\"username\":\"bob\",\"password\":\"p@ss\",\"apiKey\":\"abc123\"}");
        assertThat(masked).doesNotContain("p@ss").doesNotContain("abc123").contains("\"username\":\"bob\"");
    }

    @Test
    void masksBearerTokenJwtAndSkKey() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl";
        assertThat(SensitiveDataMasker.mask("Authorization: Bearer " + jwt)).doesNotContain(jwt);
        assertThat(SensitiveDataMasker.mask("token is " + jwt)).doesNotContain(jwt);
        assertThat(SensitiveDataMasker.mask("key sk-abcdefghijklmnop123456")).isEqualTo("key sk-****");
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        String text = "学生 2023001 提交了课程 CS101 的请假申请";
        assertThat(SensitiveDataMasker.mask(text)).isEqualTo(text);
        assertThat(SensitiveDataMasker.mask(null)).isNull();
    }

    @Test
    void masksSensitiveKeysInMapsRecursivelyAndTruncates() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("new_password", "x");
        nested.put("title", "a".repeat(50));
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("Password", "secret");
        src.put("api-key", "k");
        src.put("courseId", 42);
        src.put("nested", nested);
        src.put("list", List.of("token=abc", "ok"));

        Map<String, Object> out = SensitiveDataMasker.maskMap(src, 10);

        assertThat(out.get("Password")).isEqualTo(SensitiveDataMasker.MASK);
        assertThat(out.get("api-key")).isEqualTo(SensitiveDataMasker.MASK);
        assertThat(out.get("courseId")).isEqualTo(42);
        @SuppressWarnings("unchecked")
        Map<String, Object> outNested = (Map<String, Object>) out.get("nested");
        assertThat(outNested.get("new_password")).isEqualTo(SensitiveDataMasker.MASK);
        assertThat((String) outNested.get("title")).startsWith("aaaaaaaaaa…(+40)");
        assertThat(out.get("list")).isEqualTo(List.of("token=****", "ok"));
        // 原 Map 不被修改
        assertThat(src.get("Password")).isEqualTo("secret");
    }
}
