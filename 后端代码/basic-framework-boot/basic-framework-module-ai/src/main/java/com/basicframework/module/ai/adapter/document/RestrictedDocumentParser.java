package com.basicframework.module.ai.adapter.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;
import org.springframework.util.StringUtils;

/**
 * 受限文档解析器（K04）：TXT/Markdown 直读、PDF 走 PDFBox（按页）、DOCX 走 POI（按段）。
 *
 * <p>为什么不用 Tika 的解析器模块：`tika-parser-*-module` 在本地仓库只有 pom、没有 jar，
 * 离线解析不可用；正文解析改用 PDFBox 与 POI（都在依赖台账内、可离线构建），
 * 类型识别仍用 tika-core 的**魔数探测**（见 {@link #requireCompatibleMagic}）。
 * 偏离与依据记在 `docs/integrations/ai-platform-document-formats.md`。
 *
 * <p>四条硬约束（每条都有对应用例）：
 * <ol>
 *   <li><b>白名单格式</b>：扩展名白名单 + 魔数交叉校验（改后缀的压缩包/可执行文件会被拒绝）；</li>
 *   <li><b>有界</b>：字符总量、段落数、页数、耗时都在解析过程中检查，触顶即失败（不返回被截断的正文）；</li>
 *   <li><b>不抓取远程资源</b>：只读入参字节流，不解析内嵌对象、不跟随外部引用、不做 OCR；</li>
 *   <li><b>扫描件不造假</b>：PDF 没有文本层时返回"需要 OCR"，不生成占位正文；</li>
 *   <li><b>错误不泄内容</b>：失败只给稳定原因码与异常类型名。</li>
 * </ol>
 */
public class RestrictedDocumentParser implements DocumentParser {

    /** 支持的文件扩展名（与入库文件策略一致）。 */
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".txt", ".md", ".markdown", ".pdf", ".docx");

    /** 文本解码候选：先按 UTF-8（含 BOM），失败再试 GBK（中文文本文件常见）。 */
    private static final List<Charset> TEXT_CHARSETS = List.of(StandardCharsets.UTF_8, Charset.forName("GBK"));

    private final DocumentParserLimits limits;

    public RestrictedDocumentParser() {
        this(DocumentParserLimits.DEFAULTS);
    }

    public RestrictedDocumentParser(DocumentParserLimits limits) {
        this.limits = limits;
    }

    @Override
    public ParsedDocument parse(byte[] content, String fileName) {
        String extension = extensionOf(fileName);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new DocumentParseException(DocumentParseException.Reason.UNSUPPORTED_FORMAT, extension);
        }
        if (content == null || content.length == 0) {
            throw new DocumentParseException(DocumentParseException.Reason.CORRUPT, "empty");
        }
        requireCompatibleMagic(content, extension);
        long deadlineNanos = System.nanoTime() + limits.timeoutMillis() * 1_000_000L;
        List<ParsedSegment> segments =
                switch (extension) {
                    case ".pdf" -> parsePdf(content, deadlineNanos);
                    case ".docx" -> parseDocx(content, deadlineNanos);
                    default -> parseText(content);
                };
        int characterCount =
                segments.stream().mapToInt(segment -> segment.text().length()).sum();
        if (characterCount > limits.maxCharacters()) {
            throw new DocumentParseException(DocumentParseException.Reason.TOO_LARGE, "character-limit");
        }
        boolean ocrRequired = segments.isEmpty() && ".pdf".equals(extension);
        return new ParsedDocument(extension.substring(1), segments, characterCount, ocrRequired);
    }

    /** PDF：按页抽取文本（页码即引用位置）；没有文本层 → 需要 OCR。 */
    private List<ParsedSegment> parsePdf(byte[] content, long deadlineNanos) {
        List<ParsedSegment> segments = new ArrayList<>();
        int characters = 0;
        try (PDDocument document = Loader.loadPDF(content)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount > limits.maxPages()) {
                throw new DocumentParseException(DocumentParseException.Reason.TOO_LARGE, "page-limit");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pageCount; page++) {
                requireWithinDeadline(deadlineNanos);
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (segments.size() >= limits.maxSegments()) {
                    throw new DocumentParseException(DocumentParseException.Reason.TOO_LARGE, "segment-limit");
                }
                characters += text.length();
                if (characters > limits.maxCharacters()) {
                    throw new DocumentParseException(DocumentParseException.Reason.TOO_LARGE, "character-limit");
                }
                segments.add(new ParsedSegment(segments.size(), "第 " + page + " 页", text));
            }
        } catch (InvalidPasswordException encrypted) {
            throw DocumentParseException.of(DocumentParseException.Reason.ENCRYPTED, encrypted);
        } catch (DocumentParseException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw DocumentParseException.of(DocumentParseException.Reason.CORRUPT, failure);
        }
        return segments;
    }

    /** DOCX：按段落与表格抽文本（段号/表号即引用位置）。 */
    private List<ParsedSegment> parseDocx(byte[] content, long deadlineNanos) {
        List<ParsedSegment> segments = new ArrayList<>();
        int characters = 0;
        int paragraphNumber = 0;
        int tableNumber = 0;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            for (IBodyElement element : document.getBodyElements()) {
                requireWithinDeadline(deadlineNanos);
                if (element instanceof XWPFParagraph paragraph) {
                    characters = addSegment(segments, paragraph.getText(), "段落 " + (++paragraphNumber), characters);
                } else if (element instanceof XWPFTable table) {
                    StringBuilder tableText = new StringBuilder();
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            if (!tableText.isEmpty()) {
                                tableText.append(' ');
                            }
                            tableText.append(cell.getText().trim());
                        }
                    }
                    characters = addSegment(segments, tableText.toString(), "表格 " + (++tableNumber), characters);
                }
            }
        } catch (DocumentParseException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw DocumentParseException.of(DocumentParseException.Reason.CORRUPT, failure);
        }
        return segments;
    }

    /** TXT/Markdown：按空行分段（没有结构信息时退化为"全文"单段，不编造位置）。 */
    private List<ParsedSegment> parseText(byte[] content) {
        String text = decodeText(content);
        List<ParsedSegment> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int paragraphNumber = 0;
        for (String line : text.split("\\R", -1)) {
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    paragraphNumber++;
                    segments.add(new ParsedSegment(
                            segments.size(),
                            "段落 " + paragraphNumber,
                            current.toString().trim()));
                    current.setLength(0);
                }
                continue;
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            paragraphNumber++;
            segments.add(new ParsedSegment(
                    segments.size(), "段落 " + paragraphNumber, current.toString().trim()));
        }
        return segments;
    }

    /** 解码文本：先 UTF-8（严格），失败再试 GBK；都不行按损坏处理。 */
    private static String decodeText(byte[] content) {
        for (Charset charset : TEXT_CHARSETS) {
            try {
                String decoded = charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(content))
                        .toString();
                return !decoded.isEmpty() && decoded.charAt(0) == 0xFEFF ? decoded.substring(1) : decoded;
            } catch (CharacterCodingException notThisCharset) {
                // 换下一个候选编码
            }
        }
        throw new DocumentParseException(DocumentParseException.Reason.CORRUPT, "charset");
    }

    private int addSegment(List<ParsedSegment> segments, String rawText, String location, int characters) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            return characters;
        }
        if (segments.size() >= limits.maxSegments()) {
            throw new DocumentParseException(DocumentParseException.Reason.TOO_LARGE, "segment-limit");
        }
        int total = characters + text.length();
        if (total > limits.maxCharacters()) {
            throw new DocumentParseException(DocumentParseException.Reason.TOO_LARGE, "character-limit");
        }
        segments.add(new ParsedSegment(segments.size(), location, text));
        return total;
    }

    private static void requireWithinDeadline(long deadlineNanos) {
        if (System.nanoTime() > deadlineNanos) {
            throw new DocumentParseException(DocumentParseException.Reason.TIMEOUT, "deadline");
        }
    }

    /**
     * 魔数交叉校验：扩展名说是一回事、内容是另一回事时拒绝。
     *
     * <p>用 tika-core 的魔数探测（不依赖解析器模块）：PDF 必须是 `%PDF`，DOCX 必须是 OOXML 容器，
     * 文本类必须是可解码的文本。这样"改名成 .pdf 的可执行文件/压缩包"不会进入 PDFBox/POI 的解析路径。
     */
    private static void requireCompatibleMagic(byte[] content, String extension) {
        String detected = detect(content);
        switch (extension) {
            case ".pdf" -> {
                if (!detected.contains("pdf")) {
                    throw new DocumentParseException(DocumentParseException.Reason.CORRUPT, "magic");
                }
            }
            case ".docx" -> {
                if (!detected.contains("zip") && !detected.contains("ooxml")) {
                    throw new DocumentParseException(DocumentParseException.Reason.CORRUPT, "magic");
                }
            }
            default -> {
                if (detected.contains("zip") || detected.contains("executable")) {
                    // 文本文件不该是压缩包或可执行文件
                    throw new DocumentParseException(DocumentParseException.Reason.CORRUPT, "magic");
                }
            }
        }
    }

    private static String detect(byte[] content) {
        try {
            MediaType type = MimeTypes.getDefaultMimeTypes()
                    .detect(new ByteArrayInputStream(content), new org.apache.tika.metadata.Metadata())
                    .getBaseType();
            return type == null ? "unknown" : type.toString().toLowerCase(Locale.ROOT);
        } catch (IOException unavailable) {
            // 探测不可用时不做"拒绝"决定：后续解析器自己会拒绝非法内容
            return "unknown";
        }
    }

    private static String extensionOf(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot);
    }
}
