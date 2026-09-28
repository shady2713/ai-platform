package com.basicframework.module.ai.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.vision.AiVisionConfidenceSource;
import com.basicframework.module.ai.service.vision.AiVisionLimits;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * OCR 识别稿生成（X02）：页码与置信度来源写入正文、无正文即拒绝、不占位不截断。
 */
class AiOcrDocumentSourceTest {

    private final AiOcrDocumentSource source = new AiOcrDocumentSource();

    @Test
    void writesEveryPageWithProvenanceAndKeepsContentVerbatim() {
        AiOcrDocumentSource.Source result = source.build(
                "扫描合同",
                List.of(
                        new AiOcrPage(2, "第二页正文：金额 290.00 元。", AiVisionConfidenceSource.UNKNOWN),
                        new AiOcrPage(1, "第一页正文：甲方与乙方。", AiVisionConfidenceSource.UNKNOWN)));

        String content = new String(result.content(), StandardCharsets.UTF_8);
        // 页码按升序写入，正文原样保留（识别到什么就是什么，不做"润色"）
        assertThat(content).containsSubsequence("## 第 1 页", "第一页正文", "## 第 2 页", "第二页正文");
        assertThat(content).contains("机器识别、未经人工核验");
        assertThat(content).contains("置信度来源：未提供");
        assertThat(result.fileName()).isEqualTo("ocr-recognized.txt");
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.characterCount()).isEqualTo("第二页正文：金额 290.00 元。".length() + "第一页正文：甲方与乙方。".length());
        assertThat(result.sha256()).matches("^[0-9a-f]{64}$");
        assertThat(result.documentConfidenceSource()).isEqualTo(AiVisionConfidenceSource.UNKNOWN);
    }

    @Test
    void providerConfidenceIsPreservedPerPageAndOnlyClaimedWhenEveryPageHasIt() {
        AiOcrDocumentSource.Source allProvider = source.build(
                "识别稿",
                List.of(
                        new AiOcrPage(1, "第一页", AiVisionConfidenceSource.PROVIDER),
                        new AiOcrPage(2, "第二页", AiVisionConfidenceSource.PROVIDER)));
        String content = new String(allProvider.content(), StandardCharsets.UTF_8);

        assertThat(content).contains("置信度来源：上游提供");
        assertThat(allProvider.documentConfidenceSource()).isEqualTo(AiVisionConfidenceSource.PROVIDER);

        AiOcrDocumentSource.Source mixed = source.build(
                "识别稿",
                List.of(
                        new AiOcrPage(1, "第一页", AiVisionConfidenceSource.PROVIDER),
                        new AiOcrPage(2, "第二页", AiVisionConfidenceSource.UNKNOWN)));
        assertThat(mixed.documentConfidenceSource())
                .as("有一页没有置信度就不能在文档级声明 PROVIDER")
                .isEqualTo(AiVisionConfidenceSource.UNKNOWN);
    }

    @Test
    void blankPagesAreSkippedAndAllBlankIsRejectedInsteadOfIndexingAnEmptyVersion() {
        AiOcrDocumentSource.Source result = source.build(
                "识别稿",
                List.of(new AiOcrPage(1, "有正文", AiVisionConfidenceSource.UNKNOWN), new AiOcrPage(2, "  ", null)));

        assertThat(result.pageCount()).as("没有文字的页不产出占位正文").isEqualTo(1);
        assertThat(new String(result.content(), StandardCharsets.UTF_8)).doesNotContain("第 2 页");

        assertCode(
                () -> source.build(
                        "识别稿",
                        List.of(
                                new AiOcrPage(1, "", AiVisionConfidenceSource.UNKNOWN),
                                new AiOcrPage(2, "   ", AiVisionConfidenceSource.UNKNOWN))),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void rejectsEmptyDuplicateOrOversizedPageSets() {
        assertCode(() -> source.build("识别稿", null), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> source.build("识别稿", List.of()), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> source.build(
                        "识别稿",
                        List.of(
                                new AiOcrPage(1, "第一页", AiVisionConfidenceSource.UNKNOWN),
                                new AiOcrPage(1, "重复页码", AiVisionConfidenceSource.UNKNOWN))),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> source.build(
                        "识别稿",
                        List.of(new AiOcrPage(
                                1,
                                "长".repeat(AiVisionLimits.MAX_OCR_CHARACTERS + 1),
                                AiVisionConfidenceSource.UNKNOWN))),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertThatThrownBy(() -> new AiOcrPage(0, "页码非法", AiVisionConfidenceSource.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameInputProducesSameDigestSoReindexingIsIdempotent() {
        List<AiOcrPage> pages = List.of(new AiOcrPage(1, "同一份识别结果", AiVisionConfidenceSource.UNKNOWN));

        assertThat(source.build("识别稿", pages).sha256())
                .isEqualTo(source.build("识别稿", pages).sha256());
    }

    private static void assertCode(Runnable call, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }
}
