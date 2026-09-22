package com.basicframework.module.ai.adapter.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

/**
 * K04 受限解析器：真实文件（测试内用 PDFBox/POI 生成）+ 上限、损坏、扫描件与错误脱敏。
 *
 * <p>夹具在测试内生成而不是提交二进制：内容与断言在同一个文件里可核对，
 * 也避免仓库里出现无法审阅的二进制样本（生成库与解析库同源，等价于"真实文件夹具"）。
 */
class RestrictedDocumentParserTest {

    private static final String CHINESE_PARAGRAPH = "本季度华东区域销售净额为 740.00 元。";

    private final RestrictedDocumentParser parser = new RestrictedDocumentParser();

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pdfWithPages(int pages, boolean withText) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pages; page++) {
                PDPage pdPage = new PDPage();
                document.addPage(pdPage);
                try (PDPageContentStream stream = new PDPageContentStream(document, pdPage)) {
                    if (withText) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        stream.newLineAtOffset(50, 700);
                        stream.showText("Page " + (page + 1) + " sales report");
                        stream.endText();
                    } else {
                        // 只有图形、没有文本层：模拟扫描件
                        stream.addRect(50, 700, 100, 50);
                        stream.stroke();
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docxWithParagraphsAndTable(int paragraphs) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            for (int index = 0; index < paragraphs; index++) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.createRun().setText("第 " + (index + 1) + " 段：" + CHINESE_PARAGRAPH);
            }
            XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("客户");
            table.getRow(0).getCell(1).setText("净额 450.00");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    private static void assertReason(Throwable throwable, DocumentParseException.Reason expected) {
        assertThat(throwable).isInstanceOf(DocumentParseException.class);
        assertThat(((DocumentParseException) throwable).reason()).isEqualTo(expected);
    }

    @Test
    void parsesTextAndMarkdownIntoLocatedParagraphs() {
        ParsedDocument text = parser.parse(utf8(CHINESE_PARAGRAPH + "\n\n第二段：华东 450.00、290.00。\n"), "handbook.txt");

        assertThat(text.format()).isEqualTo("txt");
        assertThat(text.requiresOcr()).isFalse();
        assertThat(text.segments()).hasSize(2);
        assertThat(text.segments().get(0).locationRef()).isEqualTo("段落 1");
        assertThat(text.segments().get(0).text()).isEqualTo(CHINESE_PARAGRAPH);
        assertThat(text.segments().get(1).text()).contains("450.00");
        assertThat(text.characterCount()).isPositive();

        ParsedDocument markdown = parser.parse(utf8("# 标题\n\n正文内容\n"), "handbook.md");
        assertThat(markdown.format()).isEqualTo("md");
        assertThat(markdown.segments()).hasSize(2);
        assertThat(markdown.segments().get(0).text()).isEqualTo("# 标题");
    }

    @Test
    void decodesGbkTextAndStripsBom() {
        ParsedDocument gbk = parser.parse(CHINESE_PARAGRAPH.getBytes(Charset.forName("GBK")), "handbook.txt");
        assertThat(gbk.segments()).hasSize(1);
        assertThat(gbk.segments().get(0).text()).isEqualTo(CHINESE_PARAGRAPH);

        ParsedDocument bom = parser.parse(utf8("\uFEFF" + CHINESE_PARAGRAPH), "handbook.txt");
        assertThat(bom.segments().get(0).text()).isEqualTo(CHINESE_PARAGRAPH);
    }

    @Test
    void parsesDocxParagraphsAndTables() throws IOException {
        ParsedDocument docx = parser.parse(docxWithParagraphsAndTable(2), "handbook.docx");

        assertThat(docx.format()).isEqualTo("docx");
        assertThat(docx.requiresOcr()).isFalse();
        assertThat(docx.segments()).hasSize(3);
        assertThat(docx.segments().get(0).locationRef()).isEqualTo("段落 1");
        assertThat(docx.segments().get(1).locationRef()).isEqualTo("段落 2");
        assertThat(docx.segments().get(2).locationRef()).isEqualTo("表格 1");
        assertThat(docx.segments().get(2).text()).contains("450.00");
    }

    @Test
    void parsesPdfPageByPage() throws IOException {
        ParsedDocument pdf = parser.parse(pdfWithPages(2, true), "report.pdf");

        assertThat(pdf.format()).isEqualTo("pdf");
        assertThat(pdf.segments()).hasSize(2);
        assertThat(pdf.segments().get(0).locationRef()).isEqualTo("第 1 页");
        assertThat(pdf.segments().get(0).text()).contains("Page 1");
        assertThat(pdf.segments().get(1).locationRef()).isEqualTo("第 2 页");
        assertThat(pdf.segments().get(1).text()).contains("Page 2");
    }

    @Test
    void scannedPdfReportsOcrInsteadOfFakeText() throws IOException {
        ParsedDocument scanned = parser.parse(pdfWithPages(2, false), "scan.pdf");

        assertThat(scanned.requiresOcr()).as("没有文本层：提示需要 OCR").isTrue();
        assertThat(scanned.segments()).isEmpty();
        assertThat(scanned.characterCount()).isZero();
    }

    @Test
    void encryptedPdfIsReportedAsEncrypted() throws IOException {
        byte[] encrypted;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("owner-pass", "user-pass", new AccessPermission()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            encrypted = out.toByteArray();
        }
        assertThatThrownBy(() -> parser.parse(encrypted, "secret.pdf"))
                .satisfies(throwable -> assertReason(throwable, DocumentParseException.Reason.ENCRYPTED));
    }

    @Test
    void rejectsWrongMagicUnsupportedFormatsAndCorruptContent() throws IOException {
        // 压缩包改名成 .pdf / .docx：魔数不符，不进解析器
        byte[] zip = new byte[] {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00};
        assertThatThrownBy(() -> parser.parse(zip, "fake.pdf"))
                .satisfies(throwable -> assertReason(throwable, DocumentParseException.Reason.CORRUPT));
        assertThatThrownBy(() -> parser.parse(zip, "fake.txt"))
                .as("文本文件不该是压缩包")
                .satisfies(throwable -> assertReason(throwable, DocumentParseException.Reason.CORRUPT));
        assertThatThrownBy(() -> parser.parse(utf8("x"), "handbook.exe"))
                .satisfies(throwable -> assertReason(throwable, DocumentParseException.Reason.UNSUPPORTED_FORMAT));
        assertThatThrownBy(() -> parser.parse(new byte[0], "handbook.txt"))
                .satisfies(throwable -> assertReason(throwable, DocumentParseException.Reason.CORRUPT));
        // 扩展名对但内容是坏 PDF：受控失败
        assertThatThrownBy(() -> parser.parse("%PDF-1.7 broken".getBytes(StandardCharsets.UTF_8), "broken.pdf"))
                .satisfies(throwable -> assertReason(throwable, DocumentParseException.Reason.CORRUPT));
    }

    @Test
    void enforcesCharacterSegmentAndPageLimits() throws IOException {
        RestrictedDocumentParser tinyCharacters =
                new RestrictedDocumentParser(new DocumentParserLimits(20, 100, 10, 10_000));
        assertThatThrownBy(() -> tinyCharacters.parse(utf8(CHINESE_PARAGRAPH + CHINESE_PARAGRAPH), "big.txt"))
                .satisfies(throwable -> assertReason(throwable, DocumentParseException.Reason.TOO_LARGE));

        RestrictedDocumentParser tinySegments =
                new RestrictedDocumentParser(new DocumentParserLimits(10_000, 1, 10, 10_000));
        assertThatThrownBy(() -> tinySegments.parse(docxWithParagraphsAndTable(3), "many.docx"))
                .satisfies(throwable -> assertReason(throwable, DocumentParseException.Reason.TOO_LARGE));

        RestrictedDocumentParser tinyPages =
                new RestrictedDocumentParser(new DocumentParserLimits(10_000, 100, 1, 10_000));
        assertThatThrownBy(() -> tinyPages.parse(pdfWithPages(2, true), "long.pdf"))
                .satisfies(throwable -> assertReason(throwable, DocumentParseException.Reason.TOO_LARGE));
    }

    @Test
    void timesOutOnLargeDocumentWithTinyDeadline() throws IOException {
        RestrictedDocumentParser impatient =
                new RestrictedDocumentParser(new DocumentParserLimits(1_000_000, 10_000, 1_000, 1));

        assertThatThrownBy(() -> impatient.parse(docxWithParagraphsAndTable(2_000), "huge.docx"))
                .satisfies(throwable -> assertReason(throwable, DocumentParseException.Reason.TIMEOUT));
    }

    @Test
    void failuresNeverLeakFileContent() {
        String payloadWithContent = "机密内容：客户名单 A/B/C";
        try {
            parser.parse(payloadWithContent.getBytes(StandardCharsets.UTF_8), "handbook.exe");
            throw new AssertionError("应当拒绝");
        } catch (DocumentParseException failure) {
            assertThat(failure.reasonCode()).isEqualTo("parse-unsupported_format");
            assertThat(failure.getMessage()).as("错误信息只含原因码").doesNotContain("机密内容");
            assertThat(failure.getMessage()).doesNotContain("客户名单");
        }
    }
}
