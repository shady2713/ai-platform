package com.basicframework.module.ai.service.document;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID;

import com.basicframework.module.ai.service.vision.AiVisionConfidenceSource;
import com.basicframework.module.ai.service.vision.AiVisionLimits;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OCR 识别稿生成（X02）：把逐页 OCR 结果物化成可走**既有入库/切片语义**的文本文件。
 *
 * <p>为什么生成文本文件而不是"另建一套向量写入"：知识入库的版本、幂等（sourceKey + 指纹）、
 * 切片器版本、位置引用、失败不切 active 等语义都已经在 K02–K05 落地；OCR 只是**另一种正文来源**，
 * 走同一条入库流水线才能保证引用可核验、失败不替换旧版本（AT-024）这两条既有性质继续成立。
 *
 * <p>三条不伪装：
 * <ol>
 *   <li>正文只来自识别结果：一页没识别出文字就不写这一页，**不生成占位正文**（同 K04 的扫描件语义）；
 *       全部页面都没有文字则整笔拒绝，不产出一个"看似可用"的空版本；</li>
 *   <li>置信度来源逐页写明：上游给置信度才写数值，没有就写"未提供"，绝不填造数字；</li>
 *   <li>文件头明确标注"机器识别、未经人工核验"，避免下游把识别稿当人工核校过的正文。</li>
 * </ol>
 */
@Component
public class AiOcrDocumentSource {

    /**
     * 派生文件名；扩展名必须落在受控上传白名单内（`.md` 不在 F06/A07 白名单里，`.txt` 与 `.md` 同走 K04 文本分段）。
     */
    public static final String FILE_NAME = "ocr-recognized.txt";

    /** 置信度来源的展示文案（写入派生正文，供引用时可读）。 */
    private static final String CONFIDENCE_PROVIDER_LABEL = "上游提供";

    private static final String CONFIDENCE_UNKNOWN_LABEL = "未提供";

    /** 生成结果：正文、文件名、页数、字符数与摘要（摘要用于入库幂等）。 */
    public record Source(
            byte[] content,
            String fileName,
            int pageCount,
            int characterCount,
            String sha256,
            AiVisionConfidenceSource documentConfidenceSource) {}

    /**
     * 生成识别稿；输入为空、页码重复、无任何正文或超出字符上限时拒绝（不截断、不占位）。
     *
     * @param title 文档标题（来自原文档，用于稿件的首行说明）
     * @param pages 逐页识别结果
     */
    public Source build(String title, List<AiOcrPage> pages) {
        if (pages == null || pages.isEmpty()) {
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        List<AiOcrPage> ordered = new ArrayList<>(pages);
        ordered.sort(Comparator.comparingInt(AiOcrPage::page));
        Set<Integer> seenPages = new HashSet<>();
        List<AiOcrPage> withText = new ArrayList<>(ordered.size());
        int characters = 0;
        boolean allProviderConfidence = true;
        for (AiOcrPage page : ordered) {
            if (!seenPages.add(page.page())) {
                throw exception(AI_MEDIA_REQUEST_INVALID);
            }
            allProviderConfidence =
                    allProviderConfidence && page.confidenceSource() == AiVisionConfidenceSource.PROVIDER;
            if (!page.hasText()) {
                continue;
            }
            characters += page.text().length();
            if (characters > AiVisionLimits.MAX_OCR_CHARACTERS) {
                throw exception(AI_MEDIA_REQUEST_INVALID);
            }
            withText.add(page);
        }
        if (withText.isEmpty()) {
            // 一页都没识别出文字：拒绝，绝不让空正文进入索引
            throw exception(AI_MEDIA_REQUEST_INVALID);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("# ")
                .append(StringUtils.hasText(title) ? title.trim() : "OCR 识别稿")
                .append("（OCR 识别稿）\n\n")
                .append("本稿由图片文字识别结果生成：**机器识别、未经人工核验**；")
                .append("页码与识别范围逐页标注，置信度来源为\"未提供\"时不给出任何置信度数值。\n");
        for (AiOcrPage page : withText) {
            builder.append("\n## 第 ")
                    .append(page.page())
                    .append(" 页\n\n")
                    .append("（OCR 识别 · 识别范围：整页 · 置信度来源：")
                    .append(confidenceLabel(page.confidenceSource()))
                    .append(" · 未人工核验）\n\n")
                    .append(page.text())
                    .append('\n');
        }
        byte[] content = builder.toString().getBytes(StandardCharsets.UTF_8);
        AiVisionConfidenceSource documentConfidenceSource =
                allProviderConfidence ? AiVisionConfidenceSource.PROVIDER : AiVisionConfidenceSource.UNKNOWN;
        return new Source(
                content, FILE_NAME, withText.size(), characters, sha256Hex(content), documentConfidenceSource);
    }

    private static String confidenceLabel(AiVisionConfidenceSource source) {
        return source == AiVisionConfidenceSource.PROVIDER ? CONFIDENCE_PROVIDER_LABEL : CONFIDENCE_UNKNOWN_LABEL;
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            throw new IllegalStateException("SHA-256 不可用", missingAlgorithm);
        }
    }
}
