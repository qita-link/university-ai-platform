package com.campus.platform.knowledge.service;

import com.campus.platform.knowledge.config.KnowledgeProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 分块器。
 * <p>
 * 先按标题层级切分章节，再按最大字符数和重叠窗口切块；每个块保留所属标题路径，
 * 便于检索时展示“文档 > 章节”的上下文。块内容为清理过 Markdown 标记的纯文本。
 */
@Component
public class MarkdownChunker {

    private final int maxChars;
    private final int overlapChars;

    public MarkdownChunker(KnowledgeProperties properties) {
        this.maxChars = properties.maxChunkChars();
        this.overlapChars = properties.chunkOverlapChars();
    }

    public record ChunkDraft(String headingPath, String content, int charCount, int tokenEstimate) {
    }

    private record Section(String headingPath, String text) {
    }

    public List<ChunkDraft> chunk(String markdownBody) {
        if (markdownBody == null || markdownBody.isBlank()) {
            return List.of();
        }
        List<ChunkDraft> result = new ArrayList<>();
        for (Section section : sections(markdownBody)) {
            splitSection(section, result);
        }
        return result;
    }

    private List<Section> sections(String body) {
        List<Section> sections = new ArrayList<>();
        String[] lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String[] headings = new String[6];
        StringBuilder current = new StringBuilder();
        String currentPath = "";
        boolean inCode = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                current.append(line.trim()).append(' ');
                continue;
            }
            int level = headingLevel(trimmed);
            if (level > 0) {
                flush(sections, current, currentPath);
                headings[level - 1] = trimmed.substring(level).trim();
                for (int i = level; i < headings.length; i++) {
                    headings[i] = null;
                }
                currentPath = joinHeadings(headings);
                current.append(headings[level - 1]).append('\n');
                continue;
            }
            if (trimmed.isEmpty()) {
                current.append('\n');
                continue;
            }
            if (trimmed.startsWith(":::")) {
                continue;
            }
            if (isTableSeparator(trimmed)) {
                continue;
            }
            String cleaned = cleanInline(trimmed);
            if (!cleaned.isEmpty()) {
                current.append(cleaned).append('\n');
            }
        }
        flush(sections, current, currentPath);
        return sections;
    }

    private static void flush(List<Section> sections, StringBuilder current, String headingPath) {
        String text = current.toString().trim();
        current.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        String lastHeading = headingPath == null ? "" : headingPath;
        int separator = lastHeading.lastIndexOf(" > ");
        if (separator >= 0) {
            lastHeading = lastHeading.substring(separator + 3);
        }
        // 只有标题、没有正文的章节不生成块，避免检索到空壳。
        if (!lastHeading.isEmpty() && text.equals(lastHeading)) {
            return;
        }
        sections.add(new Section(headingPath, text));
    }

    private void splitSection(Section section, List<ChunkDraft> out) {
        String text = section.text();
        if (text.length() <= maxChars) {
            add(out, section.headingPath(), text);
            return;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > start + maxChars / 2) {
                    end = newline;
                }
            }
            String part = text.substring(start, end).trim();
            if (!part.isEmpty()) {
                add(out, section.headingPath(), part);
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlapChars);
        }
    }

    private static void add(List<ChunkDraft> out, String headingPath, String content) {
        String path = headingPath == null ? "" : headingPath;
        if (path.length() > 500) {
            path = path.substring(0, 500);
        }
        int chars = content.length();
        out.add(new ChunkDraft(path, content, chars, Math.max(1, (int) Math.ceil(chars / 2.0))));
    }

    private static int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && level < 6 && line.charAt(level) == '#') {
            level++;
        }
        if (level > 0 && level < line.length() && line.charAt(level) == ' ') {
            return level;
        }
        return 0;
    }

    private static String joinHeadings(String[] headings) {
        StringBuilder b = new StringBuilder();
        for (String h : headings) {
            if (h == null || h.isBlank()) {
                continue;
            }
            if (!b.isEmpty()) {
                b.append(" > ");
            }
            b.append(h);
        }
        return b.toString();
    }

    private static boolean isTableSeparator(String line) {
        return line.startsWith("|") && line.contains("-") && line.matches("^[|\\s:.-]+$");
    }

    private static String cleanInline(String line) {
        String s = line;
        s = s.replaceAll("!\\[([^\\]]*)]\\([^)]*\\)", "$1");
        s = s.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replaceAll("^\\s{0,3}>\\s?", "");
        s = s.replaceAll("^\\s*([-*+]|\\d+\\.)\\s+", "");
        s = s.replace('|', ' ');
        s = s.replaceAll("[*_`~]+", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }
}
