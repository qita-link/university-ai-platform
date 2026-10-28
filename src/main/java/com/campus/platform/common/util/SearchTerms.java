package com.campus.platform.common.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 中文全文检索的查询词构造工具（MySQL ngram BOOLEAN MODE）。
 * <p>
 * ngram 解析器会把“报到需要带什么”当成一个整体，直接查询通常匹配不到。这里把连续中文拆成
 * 2 字 ngram（“报到 到需 需要 …”），英文/数字按词保留，并去除 BOOLEAN MODE 操作符和常见问句虚词。
 * 知识库检索与论坛检索共用该规则，保证两处中文召回行为一致。
 */
public final class SearchTerms {

    /** 问句中的高频虚词/疑问词；不参与检索，避免“需要/什么”把无关内容排到前面。 */
    private static final List<String> STOP_WORDS = List.of(
            "请问", "帮我", "告诉", "介绍", "关于", "一下", "我们", "你们", "他们", "它们",
            "什么", "怎么", "怎样", "如何", "为什么", "哪些", "哪个", "多少", "是否", "可以",
            "能否", "应该", "需要", "知道", "想要", "这个", "那个", "这些", "那些", "的话",
            "以及", "还有");

    /** 单次查询最多保留的词数，避免构造出过长的 BOOLEAN 查询。 */
    private static final int MAX_TERMS = 40;

    private SearchTerms() {
    }

    /**
     * 把自然语言查询转换为 MySQL BOOLEAN MODE 查询串；无有效词时返回空串。
     */
    public static String toBooleanQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("[+\\-<>()~*\"@]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        for (String stop : STOP_WORDS) {
            cleaned = cleaned.replace(stop, " ");
        }
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < cleaned.length(); ) {
            int cp = cleaned.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                flushWord(word, terms);
                cjk.appendCodePoint(cp);
            } else if (Character.isLetterOrDigit(cp)) {
                flushCjk(cjk, terms);
                word.appendCodePoint(cp);
            } else {
                flushCjk(cjk, terms);
                flushWord(word, terms);
            }
            i += Character.charCount(cp);
        }
        flushCjk(cjk, terms);
        flushWord(word, terms);
        if (terms.isEmpty()) {
            return cleaned;
        }
        return String.join(" ", terms.stream().limit(MAX_TERMS).toList());
    }

    private static void flushCjk(StringBuilder cjk, Set<String> terms) {
        if (cjk.isEmpty()) {
            return;
        }
        String s = cjk.toString();
        cjk.setLength(0);
        if (s.length() == 1) {
            terms.add(s);
            return;
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            terms.add(s.substring(i, i + 2));
        }
    }

    private static void flushWord(StringBuilder word, Set<String> terms) {
        if (word.isEmpty()) {
            return;
        }
        String s = word.toString();
        word.setLength(0);
        if (s.length() >= 2) {
            terms.add(s);
        }
    }
}
