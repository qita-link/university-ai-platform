package com.campus.platform.knowledge.parser;

import com.campus.platform.common.exception.BusinessException;
import com.campus.platform.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把上传的文档转成纯文本（Markdown 风格），再交给既有的分块与检索链路。
 * <p>
 * 支持：{@code .md/.markdown/.txt}（UTF-8 直接读）、{@code .docx}（POI）、{@code .pdf}（PDFBox）。
 * <b>不支持</b>老式二进制 {@code .doc} 与图片扫描件（无 OCR）——这两类请先另存为 docx/pdf 文本版。
 * <p>
 * 转换只负责“把内容摊平”，不做版式还原：标题层级、样式、图片都会丢失。
 * 这是有意为之——检索要的是可切块、可全文匹配的文本。
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "markdown", "txt", "docx", "pdf");

    /** 解析结果：正文 + 从内容里推断出的标题（可能为 null，调用方用文件名兜底）。 */
    public record Extracted(String text, String titleHint, String format) {
    }

    public Extracted extract(String filename, byte[] bytes) {
        String ext = extensionOf(filename);
        try {
            return switch (ext) {
                case "md", "markdown", "txt" -> new Extracted(decodeText(bytes), null, ext);
                case "docx" -> new Extracted(readDocx(bytes), null, ext);
                case "pdf" -> new Extracted(readPdf(bytes), null, ext);
                default -> throw new BusinessException(ErrorCode.KB_DOCUMENT_INVALID_FILE,
                        "只支持 .md / .txt / .docx / .pdf 文件");
            };
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("文档解析失败: filename={}, ext={}", filename, ext, ex);
            throw new BusinessException(ErrorCode.KB_DOCUMENT_INVALID_FILE,
                    "文件解析失败，请确认不是加密或损坏的文件：" + ex.getMessage(), ex);
        }
    }

    public static boolean isSupported(String filename) {
        return SUPPORTED_EXTENSIONS.contains(extensionOf(filename));
    }

    /** 去掉扩展名后的文件名，用作标题兜底。 */
    public static String baseName(String filename) {
        if (filename == null) {
            return "未命名文档";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 文本解码：优先 UTF-8（严格模式），解不开再按 GBK 读。
     * 中文环境里记事本另存的 .txt 常常是 GBK，直接按 UTF-8 读会得到一堆乱码。
     */
    private String decodeText(byte[] bytes) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            return stripBom(text);
        } catch (CharacterCodingException ex) {
            return stripBom(new String(bytes, Charset.forName("GBK")));
        }
    }

    private static String stripBom(String text) {
        return text != null && text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    // ------------------------------------------------------------------ docx

    private String readDocx(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    String text = p.getText() == null ? "" : p.getText().trim();
                    if (!text.isEmpty()) {
                        sb.append(text).append("\n\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    appendTable(sb, table);
                }
            }
        }
        return normalize(sb.toString());
    }

    /** Word 表格转 Markdown 表格，首行当表头。 */
    private void appendTable(StringBuilder sb, XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return;
        }
        List<List<String>> cells = new ArrayList<>();
        for (XWPFTableRow row : rows) {
            List<String> line = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                line.add(cell.getText() == null ? "" : cell.getText().replace("\n", " ").trim());
            }
            cells.add(line);
        }
        int columns = cells.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) {
            return;
        }
        for (int i = 0; i < cells.size(); i++) {
            List<String> line = cells.get(i);
            sb.append('|');
            for (int c = 0; c < columns; c++) {
                sb.append(' ').append(c < line.size() ? line.get(c) : "").append(" |");
            }
            sb.append('\n');
            if (i == 0) {
                sb.append('|');
                for (int c = 0; c < columns; c++) {
                    sb.append(" --- |");
                }
                sb.append('\n');
            }
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------- pdf

    private String readPdf(byte[] bytes) throws IOException {
        try (PDDocument doc = PDDocument.load(bytes)) {
            if (doc.isEncrypted()) {
                throw new BusinessException(ErrorCode.KB_DOCUMENT_INVALID_FILE, "PDF 已加密，无法解析");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return normalize(stripper.getText(doc));
        }
    }

    /**
     * 规整空白：去掉行尾空格、把 3 个以上连续换行压成 2 个（段落分隔）。
     * 不做“把单换行合并成段”的猜测——PDF 的换行位置不可靠，合并反而会切断句子。
     */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t\\u00A0]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }
}
