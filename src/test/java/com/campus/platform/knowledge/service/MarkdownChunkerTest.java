package com.campus.platform.knowledge.service;

import com.campus.platform.knowledge.config.KnowledgeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker(new KnowledgeProperties(120, 20, 1024L, 20));

    @Test
    void parsesFrontmatterTitleAndFallsBackToFirstHeading() {
        MarkdownParser.ParsedMarkdown withFrontmatter = MarkdownParser.parse("""
                ---
                title: 入学准备
                order: 1
                ---

                # 一级标题
                正文
                """);
        assertThat(withFrontmatter.title()).isEqualTo("入学准备");
        assertThat(withFrontmatter.body()).doesNotContain("title:").contains("正文");

        MarkdownParser.ParsedMarkdown noFrontmatter = MarkdownParser.parse("# 另一个标题\n\n正文");
        assertThat(noFrontmatter.title()).isEqualTo("另一个标题");
    }

    @Test
    void splitsLongSectionsWithHeadingPathAndOverlap() {
        String body = "# 入学准备\n\n## 报到\n\n" + "重要内容。".repeat(50);
        List<MarkdownChunker.ChunkDraft> chunks = chunker.chunk(body);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(120));
        assertThat(chunks.get(0).headingPath()).contains("入学准备").contains("报到");
        assertThat(chunks.get(0).tokenEstimate()).isPositive();
    }

    @Test
    void stripsMarkdownSyntaxAndKeepsReadableText() {
        List<MarkdownChunker.ChunkDraft> chunks = chunker.chunk("""
                # 标题

                [链接文字](https://example.com) **加粗** ![图片](x.png)
                - 列表项
                """);
        assertThat(chunks).hasSize(1);
        String content = chunks.get(0).content();
        assertThat(content).contains("链接文字", "加粗", "图片", "列表项");
        assertThat(content).doesNotContain("https://example.com", "**", "x.png");
    }

    @Test
    void blankDocumentProducesNoChunks() {
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk(MarkdownParser.parse("---\ntitle: 空\n---\n").body())).isEmpty();
    }
}
