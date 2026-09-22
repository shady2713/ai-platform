package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.adapter.knowledge.QdrantRestKnowledgeIndexAdapter;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.job.AiKnowledgeCleanupJob;
import com.basicframework.module.ai.job.AiKnowledgeIngestionJob;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeChunkService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeBaseSaveDTO;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeEmbeddingClient;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeSourceReader;
import com.basicframework.module.ai.service.knowledge.lifecycle.AiKnowledgeLifecycleService;
import com.basicframework.module.ai.service.knowledge.lifecycle.dto.AiKnowledgeCleanupReportDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * K07 同步、撤销与清理端到端（真实 MySQL + 真实 Qdrant）。
 *
 * <p>覆盖 AT-028（撤销后索引残留不可读取：清理删掉切片与向量，行软删除且知识库可随之删除）
 * 与"重启继续清理"（状态驱动、分步幂等：中断后重跑从当前状态继续）。
 * 文件引用释放走 A07 的共享语义（只在最后一个引用释放时删文件）——集成上下文没有主体，
 * 释放会按 fail-closed 跳过，因此"共享文件不误删"由单测与 A07 的用例共同保证（见证据文档）。
 */
@Import(AiKnowledgeLifecycleIT.LifecycleTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiKnowledgeLifecycleIT extends AbstractPersistenceIntegrationTest {

    private static final DockerImageName QDRANT_IMAGE = DockerImageName.parse(
                    "qdrant/qdrant:v1.19.1@sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10")
            .asCompatibleSubstituteFor("qdrant/qdrant");

    private static final GenericContainer<?> QDRANT =
            new GenericContainer<>(QDRANT_IMAGE).withExposedPorts(6333).withEnv("QDRANT__SERVICE__API_KEY", "it-key");

    static {
        QDRANT.start();
    }

    @TestConfiguration
    static class LifecycleTestConfiguration {

        @Bean
        @Primary
        AiKnowledgeEmbeddingClient fixtureEmbeddingClient() {
            return (embeddingModel, texts) -> new AiKnowledgeEmbeddingClient.EmbeddingBatch(
                    texts.stream()
                            .map(AiKnowledgeLifecycleIT::deterministicVector)
                            .toList(),
                    embeddingModel,
                    8);
        }

        @Bean
        @Primary
        AiKnowledgeSourceReader fixtureSourceReader() {
            return fileId -> new AiKnowledgeSourceReader.KnowledgeSource(
                    "华东区域 8 月净额 740.00 元。\n\nC001 客户 290.00 元。".getBytes(StandardCharsets.UTF_8), "handbook.txt");
        }

        @Bean
        @Primary
        KnowledgeIndexPort knowledgeIndexPort() {
            return new QdrantRestKnowledgeIndexAdapter(
                    "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333),
                    "it-key",
                    Duration.ofSeconds(20),
                    true);
        }
    }

    static float[] deterministicVector(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            float[] vector = new float[8];
            for (int index = 0; index < vector.length; index++) {
                vector[index] = ((digest[index] & 0xFF) / 255.0f) - 0.5f;
            }
            return vector;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final String CODE = "it-lifecycle";

    @Autowired
    private AiKnowledgeBaseService baseService;

    @Autowired
    private AiKnowledgeDocumentService documentService;

    @Autowired
    private AiKnowledgeChunkService chunkService;

    @Autowired
    private AiKnowledgeLifecycleService lifecycleService;

    @Autowired
    private AiKnowledgeIngestionJob ingestionJob;

    @Autowired
    private AiKnowledgeCleanupJob cleanupJob;

    @Autowired
    private KnowledgeIndexPort indexPort;

    private Long baseId;

    @BeforeEach
    void prepare() {
        cleanUp();
        baseId = baseService.create(new AiKnowledgeBaseSaveDTO()
                .setCode(CODE)
                .setName("IT 生命周期知识库")
                .setVisibility(AiKnowledgeBaseDO.VISIBILITY_SHARED)
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(8));
        jdbcTemplate.update(
                "INSERT INTO ai_file_binding (file_id, business_type, business_key, application_id, subject_type,"
                        + " external_user_id, status, version, deleted) VALUES (9201, 'ai_knowledge_document', ?,"
                        + " 1, 'USER', 'alice', 'ACTIVE', 0, b'0')",
                CODE);
    }

    @AfterEach
    void cleanUp() {
        try {
            indexPort.deleteAll("kb_" + CODE + "_g1");
        } catch (RuntimeException missing) {
            // 集合不存在
        }
        jdbcTemplate.update("DELETE FROM ai_knowledge_ingestion_task");
        jdbcTemplate.update("DELETE FROM ai_knowledge_chunk");
        jdbcTemplate.update("DELETE FROM ai_knowledge_document_version");
        jdbcTemplate.update("DELETE FROM ai_knowledge_document");
        jdbcTemplate.update("DELETE FROM ai_knowledge_index_generation");
        jdbcTemplate.update("DELETE FROM ai_knowledge_base");
        jdbcTemplate.update("DELETE FROM ai_file_binding WHERE file_id = 9201");
        baseId = null;
    }

    private Long syncAndIndex(String sourceKey) {
        var sync = lifecycleService.sync(baseId, sourceKey, "员工手册", "drive:" + sourceKey, 9201L, "a".repeat(64));
        assertThat(ingestionJob.execute(null)).contains("成功 1");
        return sync.getDocumentId();
    }

    @Test
    void revokeThenCleanupRemovesChunksVectorsAndRowsSoKnowledgeBaseBecomesDeletable() {
        Long documentId = syncAndIndex("handbook/v1.txt");
        var document = documentService.getDocument(documentId);
        var active = documentService.getActiveVersion(documentId);
        assertThat(chunkService.listVersionChunks(active.getId())).isNotEmpty();
        assertThat(indexPort.search("kb_" + CODE + "_g1", deterministicVector("C001 客户 290.00 元。"), 5, null))
                .as("索引里有真实向量")
                .isNotEmpty();

        lifecycleService.revoke(documentId);
        assertThat(documentService.getDocument(documentId).getStatus())
                .as("先撤可见性（检索侧据此立即不可见）")
                .isEqualTo(AiKnowledgeDocumentDO.STATUS_DELETING);

        AiKnowledgeCleanupReportDTO report = lifecycleService.cleanup(documentId);
        assertThat(cleanupJob.execute(null)).as("Job 无待处理项时也不报错").contains("待处理 0");

        assertThat(report.getProcessedDocuments()).isEqualTo(1);
        assertThat(report.getDeletedChunks()).isPositive();
        assertThat(chunkService.countByGeneration(baseId, active.getIndexGeneration() == null ? 1 : 1))
                .isZero();
        assertThat(indexPort.search("kb_" + CODE + "_g1", deterministicVector("C001 客户 290.00 元。"), 5, null))
                .as("向量已回收：索引残留不可读取（AT-028）")
                .isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_knowledge_document WHERE id = ? AND deleted = b'0'",
                        Integer.class,
                        documentId))
                .as("文档行已软删除")
                .isZero();
        // 行清理干净后，知识库可以删除（K02 的"仍有文档不能删"守卫通过）
        baseService.delete(baseId, baseService.getKnowledgeBase(baseId).getVersion());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_knowledge_base WHERE id = ? AND deleted = b'0'",
                        Integer.class,
                        baseId))
                .isZero();
        assertThat(document.getKnowledgeBaseId()).isEqualTo(baseId);
    }

    @Test
    void cleanupResumesAcrossRunsAndIsIdempotent() {
        Long first = syncAndIndex("handbook/a.txt");
        Long second = syncAndIndex("handbook/b.txt");
        lifecycleService.revoke(first);
        lifecycleService.revoke(second);

        AiKnowledgeCleanupReportDTO firstRound = lifecycleService.processPendingCleanups(1);
        assertThat(firstRound.getProcessedDocuments()).isEqualTo(1);
        assertThat(firstRound.getPendingDocuments()).as("还剩一个待清理").isEqualTo(1);

        AiKnowledgeCleanupReportDTO secondRound = lifecycleService.processPendingCleanups(1);
        assertThat(secondRound.getProcessedDocuments()).isEqualTo(1);
        assertThat(secondRound.getPendingDocuments()).isZero();

        // 再跑一轮：没有待处理项（幂等，不重复删除）
        AiKnowledgeCleanupReportDTO thirdRound = lifecycleService.processPendingCleanups(1);
        assertThat(thirdRound.getProcessedDocuments()).isZero();
        assertThat(thirdRound.getPendingDocuments()).isZero();
    }

    @Test
    void orphanAuditIsCleanAfterFullCleanup() {
        Long documentId = syncAndIndex("handbook/v1.txt");
        lifecycleService.cleanup(documentId);

        AiKnowledgeCleanupReportDTO report = lifecycleService.auditOrphans(baseId, false);

        assertThat(report.getOrphans()).isEmpty();
        assertThat(report.hasPending()).isFalse();
    }
}
