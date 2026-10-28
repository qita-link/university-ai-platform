package com.campus.platform.knowledge.service;

import java.util.Locale;

/**
 * 轻量 Markdown 解析：只处理知识库需要的标题、frontmatter 和正文，不引入完整 CommonMark 依赖。
 * <p>
 * 原文仍按上传内容保存；这里只负责提取标题和可检索正文。
 */
public final class MarkdownParser {

    private MarkdownParser() {
    }

    public record ParsedMarkdown(String title, String body) {
    }

    public static ParsedMarkdown parse(String raw) {
        String text = raw == null ? "" : raw;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');

        String title = null;
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end > 0) {
                String frontmatter = text.substring(4, end);
                title = frontmatterValue(frontmatter, "title");
                int bodyStart = end + 4;
                if (bodyStart < text.length() && text.charAt(bodyStart) == '\n') {
                    bodyStart++;
                }
                text = text.substring(bodyStart);
            }
        }

        // 首页等文档可能包含 Vue 组件；脚本和样式不属于可检索内容。
        text = text.replaceAll("(?is)<!--.*?-->", " ");
        text = text.replaceAll("(?is)<script\\b.*?</script>", " ");
        text = text.replaceAll("(?is)<style\\b.*?</style>", " ");

        if (title == null || title.isBlank()) {
            title = firstHeading(text);
        }
        return new ParsedMarkdown(title == null ? null : title.trim(), text);
    }

    private static String frontmatterValue(String frontmatter, String key) {
        for (String line : frontmatter.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (k.equals(key)) {
                String v = line.substring(colon + 1).trim();
                if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                        || (v.startsWith("'") && v.endsWith("'")))) {
                    v = v.substring(1, v.length() - 1);
                }
                return v.isBlank() ? null : v;
            }
        }
        return null;
    }

    private static String firstHeading(String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return null;
    }
}
