package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeChunkService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeIndexGenerationService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeBaseSaveDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeChunkDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentSaveDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentUpsertResultDTO;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * K02 知识库与文档版本在真实 MySQL 上的数据模型验收。
 *
 * <p>覆盖卡片验收项：重复 sourceKey 正确复用/更新、版本发布后不可修改、active 版本切换与失败回退、
 * 索引代状态与维度约束、删除保护（仍有文档不能删库）、以及"存活行唯一"的函数索引语义。
 * 切片/索引代是派生数据：这里验证写入、统计与物理清理（回收由 K07 串联）。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiKnowledgePersistenceIT extends AbstractPersistenceIntegrationTest {

    private static final String CODE = "it-handbook";

    private static final String OTHER_CODE = "it-handbook-other";

    private static final String HASH_V1 = "1".repeat(64);

    private static final String HASH_V2 = "2".repeat(64);

    @Autowired
    private AiKnowledgeBaseService baseService;

    @Autowired
    private AiKnowledgeDocumentService documentService;

    @Autowired
    private AiKnowledgeChunkService chunkService;

    @Autowired
    private AiKnowledgeIndexGenerationService generationService;

    private Long baseId;

    private Long otherBaseId;

    @BeforeEach
    void prepareKnowledgeBase() {
        cleanUp();
        baseId = baseService.create(new AiKnowledgeBaseSaveDTO()
                .setCode(CODE)
                .setName("IT 知识库")
                .setVisibility(AiKnowledgeBaseDO.VISIBILITY_SHARED)
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(1536)
                .setRetentionDays(180));
        otherBaseId = baseService.create(new AiKnowledgeBaseSaveDTO()
                .setCode(OTHER_CODE)
                .setName("IT 知识库（另一个）")
                .setVisibility(AiKnowledgeBaseDO.VISIBILITY_SHARED)
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(1536));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_knowledge_chunk");
        jdbcTemplate.update("DELETE FROM ai_knowledge_document_version");
        jdbcTemplate.update("DELETE FROM ai_knowledge_document");
        jdbcTemplate.update("DELETE FROM ai_knowledge_index_generation");
        jdbcTemplate.update("DELETE FROM ai_knowledge_base");
        baseId = null;
        otherBaseId = null;
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiKnowledgeDocumentSaveDTO save(Long knowledgeBaseId, String sourceKey, String hash, Long fileId) {
        return new AiKnowledgeDocumentSaveDTO()
                .setKnowledgeBaseId(knowledgeBaseId)
                .setSourceKey(sourceKey)
                .setTitle("员工手册")
                .setSourceType(AiKnowledgeDocumentDO.SOURCE_API_SYNC)
                .setSourceRef("drive:" + sourceKey)
                .setFileId(fileId)
                .setContentHash(hash);
    }

    private static AiKnowledgeChunkDTO chunk(int index) {
        return new AiKnowledgeChunkDTO()
                .setChunkIndex(index)
                .setContentHash("c".repeat(64))
                .setTextLength(200)
                .setTokenCount(60)
                .setVectorId("kb-" + CODE + "-c" + index)
                .setLocationRef("p." + (index + 1));
    }

    @Test
    void knowledgeBaseCodeIsUniqueAmongLiveRowsAndReusableAfterDelete() {
        assertThatThrownBy(() -> baseService.create(new AiKnowledgeBaseSaveDTO()
                        .setCode(CODE)
                        .setName("重复标识")
                        .setVisibility(AiKnowledgeBaseDO.VISIBILITY_SHARED)
                        .setEmbeddingModel("text-embedding-3-small")
                        .setEmbeddingDimension(1536)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CODE_DUPLICATE));

        AiKnowledgeBaseDO knowledgeBase = baseService.getKnowledgeBase(baseId);
        baseService.delete(baseId, knowledgeBase.getVersion());

        // 软删除后同一标识可以重建（存活行唯一，而不是全表唯一）
        Long recreated = baseService.create(new AiKnowledgeBaseSaveDTO()
                .setCode(CODE)
                .setName("IT 知识库（重建）")
                .setVisibility(AiKnowledgeBaseDO.VISIBILITY_SHARED)
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(1536));
        assertThat(recreated).isNotEqualTo(baseId);
        assertThat(baseService.getByCode(CODE).getName()).isEqualTo("IT 知识库（重建）");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_knowledge_base WHERE code = ?", Integer.class, CODE))
                .as("历史行保留（软删除），可追溯")
                .isEqualTo(2);
    }

    @Test
    void ingestIsIdempotentBySourceKeyAndHash() {
        AiKnowledgeDocumentUpsertResultDTO first =
                documentService.upsert(save(baseId, "handbook/v1.pdf", HASH_V1, 501L));
        assertThat(first.isReused()).isFalse();
        assertThat(first.isCreatedVersion()).isTrue();
        assertThat(first.getVersionNo()).isEqualTo(1);

        // 同 sourceKey + 同指纹：复用，不产生新版本
        AiKnowledgeDocumentUpsertResultDTO replay =
                documentService.upsert(save(baseId, "handbook/v1.pdf", HASH_V1, 501L));
        assertThat(replay.getDocumentId()).isEqualTo(first.getDocumentId());
        assertThat(replay.getVersionId()).isEqualTo(first.getVersionId());
        assertThat(replay.isReused()).isTrue();
        assertThat(replay.isCreatedVersion()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_knowledge_document_version WHERE document_id = ?",
                        Integer.class,
                        first.getDocumentId()))
                .isEqualTo(1);

        // 指纹变化：同一文档生成新版本，active 指针不动
        AiKnowledgeDocumentUpsertResultDTO updated =
                documentService.upsert(save(baseId, "handbook/v1.pdf", HASH_V2, 502L));
        assertThat(updated.getDocumentId()).isEqualTo(first.getDocumentId());
        assertThat(updated.getVersionNo()).isEqualTo(2);
        assertThat(updated.isCreatedVersion()).isTrue();
        assertThat(documentService.getDocument(first.getDocumentId()).getActiveVersionNo())
                .isZero();

        // 另一个知识库的同名 sourceKey 是另一个文档
        AiKnowledgeDocumentUpsertResultDTO other =
                documentService.upsert(save(otherBaseId, "handbook/v1.pdf", HASH_V1, 503L));
        assertThat(other.getDocumentId()).isNotEqualTo(first.getDocumentId());
    }

    @Test
    void publishedVersionIsImmutableAndActivePointerSwitchesOnlyOnSuccess() {
        AiKnowledgeDocumentUpsertResultDTO v1 = documentService.upsert(save(baseId, "handbook/v1.pdf", HASH_V1, 501L));
        int generationNo = generationService.startGeneration(baseId);
        chunkService.replaceVersionChunks(v1.getVersionId(), generationNo, List.of(chunk(0), chunk(1)));
        generationService.activate(baseId, generationNo);
        documentService.markVersionReady(v1.getDocumentId(), v1.getVersionId(), generationNo);

        AiKnowledgeDocumentDO ready = documentService.getDocument(v1.getDocumentId());
        assertThat(ready.getStatus()).isEqualTo(AiKnowledgeDocumentDO.STATUS_READY);
        assertThat(ready.getActiveVersionNo()).isEqualTo(1);
        assertThat(documentService.getVersion(v1.getVersionId()).getChunkCount())
                .isEqualTo(2);

        // 已可用版本不可再改（含标记失败、重写切片）
        assertThatThrownBy(() -> documentService.markVersionFailed(v1.getDocumentId(), v1.getVersionId(), "late"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_IMMUTABLE));
        assertThatThrownBy(() -> chunkService.replaceVersionChunks(v1.getVersionId(), generationNo, List.of(chunk(0))))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_IMMUTABLE));

        // 新版本索引失败：旧 active 版本仍可用（AT-024）
        AiKnowledgeDocumentUpsertResultDTO v2 = documentService.upsert(save(baseId, "handbook/v1.pdf", HASH_V2, 502L));
        documentService.markVersionFailed(v2.getDocumentId(), v2.getVersionId(), "parse-failed");
        AiKnowledgeDocumentDO afterFailure = documentService.getDocument(v1.getDocumentId());
        assertThat(afterFailure.getStatus()).isEqualTo(AiKnowledgeDocumentDO.STATUS_FAILED);
        assertThat(afterFailure.getActiveVersionNo()).as("失败不改 active 指针").isEqualTo(1);
        assertThat(documentService.getActiveVersion(v1.getDocumentId()).getVersionNo())
                .isEqualTo(1);

        // 重试同一内容：不产生新版本，重新排队
        AiKnowledgeDocumentUpsertResultDTO retry =
                documentService.upsert(save(baseId, "handbook/v1.pdf", HASH_V2, 502L));
        assertThat(retry.getVersionId()).isEqualTo(v2.getVersionId());
        assertThat(retry.isCreatedVersion()).isFalse();
        assertThat(documentService.getVersion(v2.getVersionId()).getStatus())
                .isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);

        // 重试成功后切换 active，旧版本置 SUPERSEDED
        chunkService.replaceVersionChunks(v2.getVersionId(), generationNo, List.of(chunk(0)));
        documentService.markVersionReady(v2.getDocumentId(), v2.getVersionId(), generationNo);
        AiKnowledgeDocumentDO switched = documentService.getDocument(v1.getDocumentId());
        assertThat(switched.getActiveVersionNo()).isEqualTo(2);
        assertThat(documentService.getVersion(v1.getVersionId()).getStatus())
                .isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_SUPERSEDED);
    }

    @Test
    void indexGenerationLifecycleKeepsOneBuildAndSwitchesAtomically() {
        int first = generationService.startGeneration(baseId);
        assertThat(first).isEqualTo(1);
        assertThatThrownBy(() -> generationService.startGeneration(baseId))
                .as("同一知识库同时只允许一个构建中的索引代")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT));

        AiKnowledgeDocumentUpsertResultDTO v1 = documentService.upsert(save(baseId, "handbook/v1.pdf", HASH_V1, 501L));
        chunkService.replaceVersionChunks(v1.getVersionId(), first, List.of(chunk(0), chunk(1)));
        generationService.activate(baseId, first);
        documentService.markVersionReady(v1.getDocumentId(), v1.getVersionId(), first);

        AiKnowledgeIndexGenerationDO active = generationService.getActiveGeneration(baseId);
        assertThat(active.getStatus()).isEqualTo(AiKnowledgeIndexGenerationDO.STATUS_ACTIVE);
        assertThat(active.getChunkCount()).isEqualTo(2);
        assertThat(active.getDocumentCount()).isEqualTo(1);
        assertThat(active.getCollectionName()).isEqualTo("kb_" + CODE + "_g1");
        assertThat(baseService.getKnowledgeBase(baseId).getActiveGenerationNo()).isEqualTo(first);

        // 第二代：新版本（READY 版本不可改写，换代必须走新版本）→ 激活即退役第一代，指针切换
        int second = generationService.startGeneration(baseId);
        AiKnowledgeDocumentUpsertResultDTO v2 = documentService.upsert(save(baseId, "handbook/v1.pdf", HASH_V2, 502L));
        chunkService.replaceVersionChunks(v2.getVersionId(), second, List.of(chunk(0)));
        generationService.activate(baseId, second);
        documentService.markVersionReady(v2.getDocumentId(), v2.getVersionId(), second);
        assertThat(documentService.getDocument(v1.getDocumentId()).getActiveVersionNo())
                .isEqualTo(2);
        assertThat(generationService.getGeneration(baseId, first).getStatus())
                .isEqualTo(AiKnowledgeIndexGenerationDO.STATUS_RETIRED);
        assertThat(baseService.getKnowledgeBase(baseId).getActiveGenerationNo()).isEqualTo(second);
        assertThat(chunkService.countByGeneration(baseId, second)).isEqualTo(1);
        assertThat(chunkService.countByGeneration(baseId, first))
                .as("上一代切片在退役时保留（回滚与追溯依据），由 K07 显式回收")
                .isEqualTo(2);

        // 退役当前生效的一代：清空指针并物理清理切片
        generationService.retire(baseId, second);
        assertThat(baseService.getKnowledgeBase(baseId).getActiveGenerationNo()).isZero();
        assertThat(chunkService.deleteGenerationChunks(baseId, second)).isEqualTo(1);
        assertThat(chunkService.countByGeneration(baseId, second)).isZero();
    }

    @Test
    void activateRejectsDimensionDriftAndUnknownGenerationIsRejected() {
        int generationNo = generationService.startGeneration(baseId);
        // 模拟配置被改：知识库维度被改成与已建索引代不一致
        jdbcTemplate.update("UPDATE ai_knowledge_base SET embedding_dimension = 768 WHERE id = ?", baseId);

        assertThatThrownBy(() -> generationService.activate(baseId, generationNo))
                .as("维度不一致时拒绝激活，避免混入不同维度向量")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT));

        AiKnowledgeDocumentUpsertResultDTO v1 = documentService.upsert(save(baseId, "handbook/v1.pdf", HASH_V1, 501L));
        assertThatThrownBy(() -> chunkService.replaceVersionChunks(v1.getVersionId(), 99, List.of(chunk(0))))
                .as("切片必须属于已登记的索引代")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT));
        assertThatThrownBy(() -> documentService.markVersionReady(v1.getDocumentId(), v1.getVersionId(), 99))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_GENERATION_CONFLICT));
    }

    @Test
    void deleteGuardsAndDisabledKnowledgeBaseRejectNewIngest() {
        AiKnowledgeDocumentUpsertResultDTO v1 = documentService.upsert(save(baseId, "handbook/v1.pdf", HASH_V1, 501L));

        AiKnowledgeBaseDO knowledgeBase = baseService.getKnowledgeBase(baseId);
        assertThatThrownBy(() -> baseService.delete(baseId, knowledgeBase.getVersion()))
                .as("仍有文档不能删除知识库")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_NOT_EMPTY));

        // 停用后不接受新入库（停用判定先于文档状态判定）
        baseService.updateStatus(baseId, baseService.getKnowledgeBase(baseId).getVersion(), false);
        assertThatThrownBy(() -> documentService.upsert(save(baseId, "handbook/v2.pdf", HASH_V2, 502L)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_DISABLED));

        // 重新启用后：文档进入删除中就不再接受新入库（等 K07 回收完成）
        baseService.updateStatus(baseId, baseService.getKnowledgeBase(baseId).getVersion(), true);
        documentService.deleteDocument(
                v1.getDocumentId(),
                documentService.getDocument(v1.getDocumentId()).getVersion());
        assertThatThrownBy(() -> documentService.upsert(save(baseId, "handbook/v1.pdf", HASH_V1, 503L)))
                .as("同一 sourceKey 的文档删除中：拒绝新入库")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID));

        jdbcTemplate.update("UPDATE ai_knowledge_document SET deleted = b'1' WHERE knowledge_base_id = ?", baseId);
        baseService.delete(baseId, baseService.getKnowledgeBase(baseId).getVersion());
        assertThatThrownBy(() -> baseService.getKnowledgeBase(baseId))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_BASE_NOT_FOUND));
    }

    @Test
    void documentListAndVersionListAreOrderedAndScoped() {
        documentService.upsert(save(baseId, "handbook/a.pdf", HASH_V1, 501L));
        AiKnowledgeDocumentUpsertResultDTO second =
                documentService.upsert(save(baseId, "handbook/b.pdf", HASH_V1, 502L));
        documentService.upsert(save(otherBaseId, "handbook/c.pdf", HASH_V1, 503L));

        assertThat(documentService
                        .getDocumentPage(new com.basicframework.framework.common.pojo.PageParam(), baseId, null)
                        .getTotal())
                .as("分页按知识库过滤")
                .isEqualTo(2);
        assertThat(documentService.listVersions(second.getDocumentId()))
                .hasSize(1)
                .allSatisfy(version -> assertThat(version.getFileId()).isEqualTo(502L));
        assertThat(documentService
                        .getVersionPage(
                                new com.basicframework.framework.common.pojo.PageParam(), second.getDocumentId())
                        .getTotal())
                .isEqualTo(1);
    }
}
