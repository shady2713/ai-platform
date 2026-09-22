package com.basicframework.module.ai.service.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import org.junit.jupiter.api.Test;

/** K03 入库文件策略：类型、大小、空文件与指纹（纯函数）。 */
class AiKnowledgeIngestionFilePolicyTest {

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void acceptsSupportedExtensionsAndComputesSha256() {
        byte[] content = "员工手册第一版".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        for (String name : new String[] {"handbook.txt", "handbook.MD", "handbook.markdown", "a.pdf", "b.DOCX"}) {
            assertThat(AiKnowledgeIngestionFilePolicy.requireSupportedFile(name, content.length, content))
                    .as("受支持的文件名：%s", name)
                    .isEqualTo(name);
        }
        assertThat(AiKnowledgeIngestionFilePolicy.sha256(content))
                .hasSize(64)
                .isEqualTo(AiKnowledgeIngestionFilePolicy.sha256(content));
        assertThat(AiKnowledgeIngestionFilePolicy.isSha256(AiKnowledgeIngestionFilePolicy.sha256(content)))
                .isTrue();
        assertThat(AiKnowledgeIngestionFilePolicy.isSha256("A".repeat(64))).isFalse();
    }

    @Test
    void rejectsUnsupportedTypeEmptyAndOversizedFiles() {
        byte[] content = new byte[] {1, 2, 3};

        for (String name : new String[] {"handbook.exe", "handbook", "handbook.", "handbook.zip"}) {
            assertThatThrownBy(() -> AiKnowledgeIngestionFilePolicy.requireSupportedFile(name, 3, content))
                    .as("不支持的类型：%s", name)
                    .satisfies(throwable ->
                            assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_TYPE_UNSUPPORTED));
        }
        assertThat(AiKnowledgeIngestionFilePolicy.requireSupportedFile("handbook.docx.txt", 3, content))
                .as("以最后一个扩展名判定：.docx.txt 是文本文件")
                .isEqualTo("handbook.docx.txt");
        assertThatThrownBy(() -> AiKnowledgeIngestionFilePolicy.requireSupportedFile("a.txt", 0, new byte[0]))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID));
        assertThatThrownBy(() -> AiKnowledgeIngestionFilePolicy.requireSupportedFile(null, 3, content))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID));
        assertThatThrownBy(
                        () -> AiKnowledgeIngestionFilePolicy.requireSupportedFile("a".repeat(300) + ".txt", 3, content))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID));
        assertThatThrownBy(() -> AiKnowledgeIngestionFilePolicy.requireSupportedFile(
                        "big.pdf", AiKnowledgeIngestionFilePolicy.MAX_FILE_BYTES + 1, content))
                .as("超过单文件上限必须拒绝，而不是截断")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_TOO_LARGE));
        assertThatThrownBy(() -> AiKnowledgeIngestionFilePolicy.sha256(null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID));
    }

    @Test
    void crossChecksContentTypeWithoutTrustingIt() {
        AiKnowledgeIngestionFilePolicy.requireCompatibleContentType(null);
        AiKnowledgeIngestionFilePolicy.requireCompatibleContentType("text/plain");
        AiKnowledgeIngestionFilePolicy.requireCompatibleContentType("text/markdown; charset=utf-8");
        AiKnowledgeIngestionFilePolicy.requireCompatibleContentType("application/pdf");
        AiKnowledgeIngestionFilePolicy.requireCompatibleContentType("application/octet-stream");
        AiKnowledgeIngestionFilePolicy.requireCompatibleContentType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        assertThatThrownBy(() -> AiKnowledgeIngestionFilePolicy.requireCompatibleContentType("application/zip"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_TYPE_UNSUPPORTED));
        assertThatThrownBy(() -> AiKnowledgeIngestionFilePolicy.requireCompatibleContentType("image/png"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_TYPE_UNSUPPORTED));
    }
}
