package com.basicframework.module.ai.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import org.junit.jupiter.api.Test;

/** K02 状态与取值约束：白名单、标识模式、维度与状态机。 */
class AiKnowledgeStatesTest {

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void normalizesAndValidatesCodesSourceKeysModelsAndHashes() {
        assertThat(AiKnowledgeStates.requireCode("HandBook-2026")).isEqualTo("handbook-2026");
        assertThat(AiKnowledgeStates.requireSourceKey(" handbook/manual:v2 ")).isEqualTo("handbook/manual:v2");
        assertThat(AiKnowledgeStates.requireEmbeddingModel("text-embedding-3-small"))
                .isEqualTo("text-embedding-3-small");
        assertThat(AiKnowledgeStates.requireContentHash("A".repeat(64))).isEqualTo("a".repeat(64));
        assertThat(AiKnowledgeStates.requireVectorId("kb-1-v2-c0")).isEqualTo("kb-1-v2-c0");

        for (String code : new String[] {null, "", "A", "1abc", "with space", "x".repeat(65)}) {
            assertThatThrownBy(() -> AiKnowledgeStates.requireCode(code))
                    .as("非法知识库标识必须被拒绝：%s", code)
                    .satisfies(
                            throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID));
        }
        for (String sourceKey : new String[] {null, "", "with space", "a".repeat(129), "q\"uote"}) {
            assertThatThrownBy(() -> AiKnowledgeStates.requireSourceKey(sourceKey))
                    .as("非法 sourceKey 必须被拒绝：%s", sourceKey)
                    .satisfies(
                            throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_SOURCE_KEY_INVALID));
        }
        for (String hash : new String[] {null, "", "abc", "z".repeat(64), "a".repeat(63)}) {
            assertThatThrownBy(() -> AiKnowledgeStates.requireContentHash(hash))
                    .as("非法指纹必须被拒绝：%s", hash)
                    .satisfies(
                            throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_SOURCE_KEY_INVALID));
        }
    }

    @Test
    void rejectsUnknownStatesAndOutOfRangeNumbers() {
        assertThat(AiKnowledgeStates.requireVisibility("shared")).isEqualTo(AiKnowledgeBaseDO.VISIBILITY_SHARED);
        assertThat(AiKnowledgeStates.requireBaseStatus("enabled")).isEqualTo(AiKnowledgeBaseDO.STATUS_ENABLED);
        assertThat(AiKnowledgeStates.requireSourceType(null)).isEqualTo(AiKnowledgeDocumentDO.SOURCE_UPLOAD);
        assertThat(AiKnowledgeStates.requireDocumentStatus("ready")).isEqualTo(AiKnowledgeDocumentDO.STATUS_READY);

        assertThatThrownBy(() -> AiKnowledgeStates.requireVisibility("PUBLIC"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID));
        assertThatThrownBy(() -> AiKnowledgeStates.requireDocumentStatus("PARSED"))
                .as("未知文档状态必须拒绝，不能默认回退")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID));
        assertThatThrownBy(() -> AiKnowledgeStates.requireGenerationStatus("READY"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID));

        assertThat(AiKnowledgeStates.requireDimension(1536)).isEqualTo(1536);
        assertThat(AiKnowledgeStates.requireRetentionDays(null)).isEqualTo(365);
        for (Integer dimension : new Integer[] {null, 0, -1, 8_193}) {
            assertThatThrownBy(() -> AiKnowledgeStates.requireDimension(dimension))
                    .satisfies(
                            throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID));
        }
        assertThatThrownBy(() -> AiKnowledgeStates.requireRetentionDays(0))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID));
        assertThatThrownBy(() -> AiKnowledgeStates.requireRetentionDays(3_651))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID));
    }

    @Test
    void documentStateMachineOnlyAllowsForwardProgress() {
        assertThat(AiKnowledgeStates.documentTransitionAllowed(
                        AiKnowledgeDocumentDO.STATUS_PENDING, AiKnowledgeDocumentDO.STATUS_PARSING))
                .isTrue();
        assertThat(AiKnowledgeStates.documentTransitionAllowed(
                        AiKnowledgeDocumentDO.STATUS_PENDING, AiKnowledgeDocumentDO.STATUS_INDEXING))
                .as("允许跳过解析（例如纯文本）")
                .isTrue();
        assertThat(AiKnowledgeStates.documentTransitionAllowed(
                        AiKnowledgeDocumentDO.STATUS_READY, AiKnowledgeDocumentDO.STATUS_INDEXING))
                .as("已有可用版本时重新入库是允许的（生成新版本前先回索引中）")
                .isTrue();
        assertThat(AiKnowledgeStates.documentTransitionAllowed(
                        AiKnowledgeDocumentDO.STATUS_DELETING, AiKnowledgeDocumentDO.STATUS_READY))
                .as("删除中不可回到可用")
                .isFalse();
        assertThat(AiKnowledgeStates.documentTransitionAllowed(
                        AiKnowledgeDocumentDO.STATUS_FAILED, AiKnowledgeDocumentDO.STATUS_PARSING))
                .isFalse();
        assertThat(AiKnowledgeStates.documentTransitionAllowed(null, AiKnowledgeDocumentDO.STATUS_READY))
                .isFalse();
    }

    @Test
    void generationStateMachineOnlyAllowsForwardProgress() {
        assertThat(AiKnowledgeStates.generationTransitionAllowed(
                        AiKnowledgeIndexGenerationDO.STATUS_BUILDING, AiKnowledgeIndexGenerationDO.STATUS_ACTIVE))
                .isTrue();
        assertThat(AiKnowledgeStates.generationTransitionAllowed(
                        AiKnowledgeIndexGenerationDO.STATUS_ACTIVE, AiKnowledgeIndexGenerationDO.STATUS_RETIRED))
                .isTrue();
        assertThat(AiKnowledgeStates.generationTransitionAllowed(
                        AiKnowledgeIndexGenerationDO.STATUS_RETIRED, AiKnowledgeIndexGenerationDO.STATUS_ACTIVE))
                .as("退役的索引代不可复活")
                .isFalse();
        assertThat(AiKnowledgeStates.generationTransitionAllowed(
                        AiKnowledgeIndexGenerationDO.STATUS_FAILED, AiKnowledgeIndexGenerationDO.STATUS_ACTIVE))
                .isFalse();
        assertThat(AiKnowledgeStates.generationTransitionAllowed(
                        AiKnowledgeIndexGenerationDO.STATUS_BUILDING, AiKnowledgeIndexGenerationDO.STATUS_RETIRED))
                .isFalse();
    }

    @Test
    void versionImmutabilityIsReadFromStatus() {
        assertThat(new AiKnowledgeDocumentVersionDO()
                        .setStatus(AiKnowledgeDocumentVersionDO.STATUS_INDEXING)
                        .immutable())
                .isFalse();
        assertThat(new AiKnowledgeDocumentVersionDO()
                        .setStatus(AiKnowledgeDocumentVersionDO.STATUS_FAILED)
                        .immutable())
                .isFalse();
        assertThat(new AiKnowledgeDocumentVersionDO()
                        .setStatus(AiKnowledgeDocumentVersionDO.STATUS_READY)
                        .immutable())
                .isTrue();
        assertThat(new AiKnowledgeDocumentVersionDO()
                        .setStatus(AiKnowledgeDocumentVersionDO.STATUS_SUPERSEDED)
                        .immutable())
                .isTrue();
    }
}
