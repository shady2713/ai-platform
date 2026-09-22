package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.job.AiKnowledgeIngestionJob;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeBaseSaveDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionService;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionTaskDO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionRequestDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionResultDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionTaskLeaseDTO;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * K03 文档上传与入库任务在真实 MySQL 上的验收。
 *
 * <p>覆盖卡片验收项：上传完成与任务创建同事务（无悬空状态）、重复入库不产生重复可见版本、
 * 失败不回显原始异常（只落稳定原因码）；并验证租约栅栏、过期恢复、重试上限与人工重试。
 *
 * <p>文件绑定行直接插入：`ai_file_binding.file_id` 是**逻辑引用**（无物理外键），
 * 真实上传路径由 A07 的集成用例覆盖；本用例聚焦入库编排与任务状态机。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiKnowledgeIngestionIT extends AbstractPersistenceIntegrationTest {

    private static final String CODE = "it-ingest";

    private static final String HASH_V1 = "1".repeat(64);

    private static final String HASH_V2 = "2".repeat(64);

    private static final Long FILE_ID = 9_001L;

    private static final Long OTHER_FILE_ID = 9_002L;

    @Autowired
    private AiKnowledgeBaseService baseService;

    @Autowired
    private AiKnowledgeDocumentService documentService;

    @Autowired
    private AiKnowledgeIngestionService ingestionService;

    @Autowired
    private AiKnowledgeIngestionJob ingestionJob;

    private Long baseId;

    @BeforeEach
    void prepare() {
        cleanUp();
        baseId = baseService.create(new AiKnowledgeBaseSaveDTO()
                .setCode(CODE)
                .setName("IT 入库知识库")
                .setVisibility(AiKnowledgeBaseDO.VISIBILITY_SHARED)
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(1536));
        insertBinding(FILE_ID, "ai_knowledge_document", CODE);
        insertBinding(OTHER_FILE_ID, "ai_knowledge_document", "other-base");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_knowledge_ingestion_task");
        jdbcTemplate.update("DELETE FROM ai_knowledge_chunk");
        jdbcTemplate.update("DELETE FROM ai_knowledge_document_version");
        jdbcTemplate.update("DELETE FROM ai_knowledge_document");
        jdbcTemplate.update("DELETE FROM ai_knowledge_index_generation");
        jdbcTemplate.update("DELETE FROM ai_knowledge_base");
        jdbcTemplate.update("DELETE FROM ai_file_binding WHERE file_id IN (?, ?)", FILE_ID, OTHER_FILE_ID);
        baseId = null;
    }

    private void insertBinding(Long fileId, String businessType, String businessKey) {
        jdbcTemplate.update(
                "INSERT INTO ai_file_binding (file_id, business_type, business_key, application_id, subject_type,"
                        + " external_user_id, status, version, deleted) VALUES (?, ?, ?, 1, 'USER', 'alice',"
                        + " 'ACTIVE', 0, b'0')",
                fileId,
                businessType,
                businessKey);
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiKnowledgeIngestionRequestDTO request(Long fileId, String sourceKey, String hash) {
        return new AiKnowledgeIngestionRequestDTO()
                .setKnowledgeBaseId(null)
                .setSourceKey(sourceKey)
                .setTitle("员工手册")
                .setSourceType(AiKnowledgeDocumentDO.SOURCE_UPLOAD)
                .setFileId(fileId)
                .setContentHash(hash);
    }

    private AiKnowledgeIngestionResultDTO ingest(Long fileId, String sourceKey, String hash) {
        return ingestionService.ingest(request(fileId, sourceKey, hash).setKnowledgeBaseId(baseId));
    }

    private long taskCount(Long versionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_knowledge_ingestion_task WHERE document_version_id = ?",
                Long.class,
                versionId);
    }

    @Test
    void ingestCreatesVersionAndTaskInTheSameTransaction() {
        AiKnowledgeIngestionResultDTO result = ingest(FILE_ID, "handbook/v1.pdf", HASH_V1);

        assertThat(result.getDocumentId()).isNotNull();
        assertThat(result.getTaskId()).isNotNull();
        assertThat(result.isCreatedVersion()).isTrue();
        AiKnowledgeIngestionTaskDO task = ingestionService.getTask(result.getTaskId());
        assertThat(task.getStatus()).isEqualTo(AiKnowledgeIngestionTaskDO.STATUS_QUEUED);
        assertThat(task.getAttemptCount()).isZero();
        assertThat(task.getDocumentVersionId()).isEqualTo(result.getVersionId());
        assertThat(task.getTaskKind()).isEqualTo(AiKnowledgeIngestionTaskDO.KIND_PARSE);
        assertThat(documentService.getDocument(result.getDocumentId()).getStatus())
                .isEqualTo(AiKnowledgeDocumentDO.STATUS_PENDING);
        assertThat(documentService.getVersion(result.getVersionId()).getStatus())
                .isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_INDEXING);
    }

    @Test
    void repeatedIngestReusesVersionAndTaskWithoutDuplicates() {
        AiKnowledgeIngestionResultDTO first = ingest(FILE_ID, "handbook/v1.pdf", HASH_V1);
        AiKnowledgeIngestionResultDTO replay = ingest(FILE_ID, "handbook/v1.pdf", HASH_V1);

        assertThat(replay.getDocumentId()).isEqualTo(first.getDocumentId());
        assertThat(replay.getVersionId()).isEqualTo(first.getVersionId());
        assertThat(replay.getTaskId()).isEqualTo(first.getTaskId());
        assertThat(replay.isReused()).isTrue();
        assertThat(replay.isCreatedVersion()).isFalse();
        assertThat(taskCount(first.getVersionId())).as("同版本只允许一条任务").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_knowledge_document_version WHERE document_id = ?",
                        Long.class,
                        first.getDocumentId()))
                .as("重复入库不产生重复可见版本")
                .isEqualTo(1);

        // 指纹变化：新版本 + 新任务，旧版本仍是 active 指针的目标（索引尚未成功）
        AiKnowledgeIngestionResultDTO updated = ingest(FILE_ID, "handbook/v1.pdf", HASH_V2);
        assertThat(updated.getDocumentId()).isEqualTo(first.getDocumentId());
        assertThat(updated.getVersionNo()).isEqualTo(2);
        assertThat(updated.getTaskId()).isNotEqualTo(first.getTaskId());
        assertThat(taskCount(updated.getVersionId())).isEqualTo(1);
    }

    @Test
    void foreignOrUnknownFileIsRejectedWithoutCreatingAnything() {
        assertThatThrownBy(() -> ingest(OTHER_FILE_ID, "handbook/v1.pdf", HASH_V1))
                .as("文件属于别的知识库：拒绝而不是改归属")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_knowledge_document WHERE knowledge_base_id = ?", Long.class, baseId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_knowledge_ingestion_task WHERE knowledge_base_id = ?",
                        Long.class,
                        baseId))
                .isZero();

        assertThatThrownBy(() -> ingest(9_999L, "handbook/v1.pdf", HASH_V1))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID));
        assertThatThrownBy(() -> ingest(FILE_ID, "handbook/v1.pdf", "not-a-hash"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_SOURCE_KEY_INVALID));
    }

    @Test
    void claimHeartbeatAndFinishRespectTheLeaseFence() {
        AiKnowledgeIngestionResultDTO result = ingest(FILE_ID, "handbook/v1.pdf", HASH_V1);

        List<AiKnowledgeIngestionTaskLeaseDTO> leases = ingestionService.claim("worker-a", 5, 60);
        assertThat(leases).hasSize(1);
        AiKnowledgeIngestionTaskLeaseDTO lease = leases.get(0);
        assertThat(lease.taskId()).isEqualTo(result.getTaskId());
        assertThat(lease.epoch()).isEqualTo(1);
        assertThat(ingestionService.getTask(result.getTaskId()).getStatus())
                .isEqualTo(AiKnowledgeIngestionTaskDO.STATUS_RUNNING);
        assertThat(ingestionService.claim("worker-b", 5, 60))
                .as("已被领取的任务不会被第二个 worker 拿到")
                .isEmpty();

        assertThat(ingestionService.heartbeat(lease, 60)).isTrue();
        assertThat(ingestionService.finish(lease, AiKnowledgeIngestionTaskDO.STATUS_SUCCEEDED, null))
                .isTrue();
        AiKnowledgeIngestionTaskDO finished = ingestionService.getTask(result.getTaskId());
        assertThat(finished.getStatus()).isEqualTo(AiKnowledgeIngestionTaskDO.STATUS_SUCCEEDED);
        assertThat(finished.getLeaseOwner()).isNull();
        assertThat(ingestionService.finish(lease, AiKnowledgeIngestionTaskDO.STATUS_SUCCEEDED, null))
                .as("同一租约不能写两次终态")
                .isFalse();
    }

    @Test
    void expiredLeaseIsRecoveredAndRetriedUntilMaxAttempts() {
        AiKnowledgeIngestionResultDTO result = ingest(FILE_ID, "handbook/v1.pdf", HASH_V1);

        // 第一次领取后让租约立即过期
        AiKnowledgeIngestionTaskLeaseDTO lease =
                ingestionService.claim("worker-a", 5, 1).get(0);
        jdbcTemplate.update(
                "UPDATE ai_knowledge_ingestion_task SET lease_expires_time = NOW() - INTERVAL 1 MINUTE WHERE id = ?",
                result.getTaskId());
        assertThat(ingestionService.recoverExpiredLeases(0, 50)).isEqualTo(1);
        AiKnowledgeIngestionTaskDO recovered = ingestionService.getTask(result.getTaskId());
        assertThat(recovered.getStatus()).as("未达上限：回到待领取").isEqualTo(AiKnowledgeIngestionTaskDO.STATUS_QUEUED);
        assertThat(recovered.getAttemptCount()).isEqualTo(1);

        // 过期租约被接管后，旧 worker 写不进结果（栅栏）
        assertThat(ingestionService.finish(lease, AiKnowledgeIngestionTaskDO.STATUS_SUCCEEDED, null))
                .as("租约已失效：旧 worker 的结果不算数")
                .isFalse();
        assertThat(ingestionService.heartbeat(lease, 60)).isFalse();

        // 反复过期直到达到上限 → FAILED（原因码为 lease-expired）
        int guard = 0;
        while (!AiKnowledgeIngestionTaskDO.STATUS_FAILED.equals(
                        ingestionService.getTask(result.getTaskId()).getStatus())
                && guard++ < 6) {
            assertThat(ingestionService.claim("worker-a", 5, 1))
                    .as("第 %s 轮仍应可领取（未达重试上限）", guard)
                    .hasSize(1);
            jdbcTemplate.update(
                    "UPDATE ai_knowledge_ingestion_task SET lease_expires_time = NOW() - INTERVAL 1 MINUTE"
                            + " WHERE id = ?",
                    result.getTaskId());
            assertThat(ingestionService.recoverExpiredLeases(0, 50)).isEqualTo(1);
        }
        AiKnowledgeIngestionTaskDO exhausted = ingestionService.getTask(result.getTaskId());
        assertThat(exhausted.getStatus()).isEqualTo(AiKnowledgeIngestionTaskDO.STATUS_FAILED);
        assertThat(exhausted.getLastErrorCode()).isEqualTo("lease-expired");
    }

    @Test
    void manualRetryRequeuesFailedTaskAndRejectsRunningOne() {
        AiKnowledgeIngestionResultDTO result = ingest(FILE_ID, "handbook/v1.pdf", HASH_V1);
        AiKnowledgeIngestionTaskLeaseDTO lease =
                ingestionService.claim("worker-a", 5, 60).get(0);
        assertThat(ingestionService.finish(lease, AiKnowledgeIngestionTaskDO.STATUS_FAILED, "parser-unavailable"))
                .isTrue();

        AiKnowledgeIngestionTaskDO failed = ingestionService.getTask(result.getTaskId());
        assertThat(failed.getStatus()).isEqualTo(AiKnowledgeIngestionTaskDO.STATUS_FAILED);
        ingestionService.retry(result.getTaskId(), failed.getVersion());

        AiKnowledgeIngestionTaskDO requeued = ingestionService.getTask(result.getTaskId());
        assertThat(requeued.getStatus()).isEqualTo(AiKnowledgeIngestionTaskDO.STATUS_QUEUED);
        assertThat(requeued.getAttemptCount()).as("人工重试重置尝试计数").isZero();

        // 排队中的任务不接受人工重试
        assertThatThrownBy(() -> ingestionService.retry(result.getTaskId(), requeued.getVersion()))
                .satisfies(throwable ->
                        assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_INGESTION_TASK_STATE_INVALID));
    }

    /**
     * 入库 Job 在"没有可用索引服务"时**不假装成功**：任务与版本/文档都以稳定原因码失败，
     * 旧可用版本不受影响（AT-024）。
     *
     * <p>K05 接入后解析器已就位；本用例的上下文没有配置向量服务（K01 的适配器不是 Spring Bean），
     * 因此失败原因是 `index-service-unavailable`——这正是"缺什么就报什么"的 fail-closed 行为。
     */
    @Test
    void jobFailsHonestlyWhenIndexingIsUnavailable() {
        AiKnowledgeIngestionResultDTO result = ingest(FILE_ID, "handbook/v1.pdf", HASH_V1);

        String summary = ingestionJob.execute(null);

        assertThat(summary).contains("失败 1");
        AiKnowledgeIngestionTaskDO task = ingestionService.getTask(result.getTaskId());
        assertThat(task.getStatus()).as("没有解析实现时不假装成功").isEqualTo(AiKnowledgeIngestionTaskDO.STATUS_FAILED);
        assertThat(task.getLastErrorCode()).as("失败只落稳定原因码").isEqualTo("index-service-unavailable");
        assertThat(documentService.getDocument(result.getDocumentId()).getStatus())
                .isEqualTo(AiKnowledgeDocumentDO.STATUS_FAILED);
        assertThat(documentService.getVersion(result.getVersionId()).getStatus())
                .isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_FAILED);
        assertThat(documentService.getVersion(result.getVersionId()).getFailureReason())
                .isEqualTo("index-service-unavailable");
        assertThat(documentService.getDocument(result.getDocumentId()).getActiveVersionNo())
                .as("失败不改 active 指针（AT-024）")
                .isZero();
    }
}
