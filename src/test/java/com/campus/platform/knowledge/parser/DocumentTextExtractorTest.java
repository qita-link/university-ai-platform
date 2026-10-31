package com.campus.platform.knowledge.parser;

import com.campus.platform.common.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void readsPlainMarkdownAndText() {
        byte[] bytes = "# 标题\n\n正文内容".getBytes(StandardCharsets.UTF_8);
        assertThat(extractor.extract("a.md", bytes).text()).contains("标题", "正文内容");
        assertThat(extractor.extract("a.txt", bytes).text()).contains("正文内容");
    }

    @Test
    void fallsBackToGbkForLegacyTextFiles() {
        // 中文 Windows 记事本另存的 .txt 常见是 GBK，按 UTF-8 解会失败，必须能回退
        byte[] gbk = "课程表说明".getBytes(Charset.forName("GBK"));
        assertThat(extractor.extract("legacy.txt", gbk).text()).isEqualTo("课程表说明");
    }

    @Test
    void stripsUtf8Bom() {
        byte[] withBom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        assertThat(extractor.extract("bom.md", withBom).text()).isEqualTo("hi");
    }

    @Test
    void readsDocxParagraphsAndTables() throws Exception {
        byte[] docx;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("第一条说明");
            doc.createParagraph().createRun().setText("第二条说明");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("课程");
            table.getRow(0).getCell(1).setText("学分");
            table.getRow(1).getCell(0).setText("高等数学");
            table.getRow(1).getCell(1).setText("4");
            doc.write(out);
            docx = out.toByteArray();
        }

        String text = extractor.extract("syllabus.docx", docx).text();
        assertThat(text).contains("第一条说明", "第二条说明");
        // 表格转成 Markdown 表格，表头与数据都在
        assertThat(text).contains("| 课程 | 学分 |", "| 高等数学 | 4 |");
    }

    @Test
    void readsPdfText() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Campus AI Platform Handbook");
                cs.endText();
            }
            doc.save(out);
            pdf = out.toByteArray();
        }

        assertThat(extractor.extract("handbook.pdf", pdf).text()).contains("Campus AI Platform Handbook");
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> extractor.extract("photo.png", new byte[]{1, 2, 3}))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只支持");
    }

    @Test
    void rejectsBrokenFile() {
        assertThatThrownBy(() -> extractor.extract("broken.pdf", new byte[]{1, 2, 3}))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    void helpersBehaveAsExpected() {
        assertThat(DocumentTextExtractor.isSupported("a.PDF")).isTrue();
        assertThat(DocumentTextExtractor.isSupported("a.doc")).isFalse();
        assertThat(DocumentTextExtractor.baseName("教学大纲.docx")).isEqualTo("教学大纲");
        assertThat(DocumentTextExtractor.baseName("noext")).isEqualTo("noext");
    }
}
