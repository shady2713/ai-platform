package com.basicframework.server.integration;

import static com.basicframework.server.integration.Q09RestoreDrillSupport.ALICE;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.APP_CODE;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.BOB;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.CHUNK_TEXTS;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.EMBEDDING_DIMENSION;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.ENDPOINT_CREDENTIAL;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.FILE_CONTENT;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.KNOWLEDGE_BASE_CODE;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.MYSQL;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.ORIGINAL_REPORT_DATA;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.QDRANT_API_KEY;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.REPORT_CODE;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.REPORT_KEY;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.RUN_KEY;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.WRONG_KEY_BASE64;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.aliceGrantId;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.aliceKnowledgeGrantId;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.applicationId;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.applicationSecret;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.backupBytes;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.backupFile;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.backupSha256;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.chunkVector;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.collectionName;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.createIndexSnapshot;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.destroyedRows;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.documentId;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.documentVersionId;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.expectedVectorIds;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.fileConfigId;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.fileId;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.generationNo;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.indexPort;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.indexSnapshotBytes;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.indexSnapshotName;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.knowledgeBaseFilter;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.knowledgeBaseId;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.metric;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.metricsSoFar;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.modelEndpointId;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.preBackupTableCounts;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.recordEnvironment;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.recoverIndexSnapshot;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.reportId;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.seconds;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.sha256;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.snapshotBytes;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.tBackupEndMillis;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.tBackupStartMillis;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.tDestroyEndMillis;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.tDestroyStartMillis;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.tIndexRecoverEndMillis;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.tPostBackupWriteMillis;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.tRestoreEndMillis;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.tRestoreStartMillis;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.tVerifiedEndMillis;
import static com.basicframework.server.integration.Q09RestoreDrillSupport.tableRowCounts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.adapter.knowledge.QdrantRestKnowledgeIndexAdapter;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeChunkService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeIndexGenerationService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeBaseSaveDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeChunkDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentSaveDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentUpsertResultDTO;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeChunker;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.report.persistence.AiReportService;
import com.basicframework.module.ai.service.report.persistence.dto.AiReportSaveDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import com.basicframework.module.infra.service.file.FileConfigService;
import com.basicframework.server.BasicFrameworkServerApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

/**
 * Q09 恢复演练（真实 MySQL 8.4 + Redis 7 + Qdrant 1.19 容器）：
 * <b>备份 → 破坏 → 恢复 → 重启后业务可用性</b>，对应 AT-030（索引快照恢复）与 AT-066（索引/DB 回退演练）。
 *
 * <p>这不是"检查备份文件存在"：备份用容器内真实 {@code mysqldump} 导出到宿主机并记录体积与 SHA-256；
 * 破坏真实删除/改写授权、知识文档版本与切片、报表版本，并清空向量索引；恢复用真实 {@code mysql}
 * 客户端按 备份文件 → MySQL → 索引快照 的顺序回放；恢复后由**新启动的应用上下文**
 * （{@link DirtiesContext} 模拟进程重启）经 A01/A03/K02/R04 的真实服务链路断言
 * "权限、引用、报表真的可用"，而不是只看文件。
 *
 * <p>容器装配、演练常量与跨阶段状态在 {@link Q09RestoreDrillSupport}（拆分的支持类，不含用例）。
 *
 * <p>演练口径（本机容器，非生产）：单机 Docker，MySQL/Redis/Qdrant 均为本机容器，
 * 数据规模为演练样本（见 {@code Q09-DRILL-METRIC} 输出），RTO/RPO 数字只对本口径有效。
 *
 * <p>与生产的已知偏差（如实记录，详见 {@code docs/operations/q09-restore-drill.md}）：
 * 恢复窗口内应用上下文并未真正停机（仅关闭 Quartz 调度），生产恢复顺序要求先停应用；
 * 文件存储只覆盖 DB 存储（外部对象存储/本地磁盘不在 mysqldump 内）。
 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = BasicFrameworkServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.task.scheduling.enabled=false",
            // 恢复窗口内不应有后台任务写库（生产口径是停机恢复；这里停调度而不是停进程，见类注释）
            "spring.quartz.auto-startup=false",
            "basic-framework.security.credential-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
@Import(Q09RestoreDrillSupport.DrillScopeResolver.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Q09RestoreDrillIT {

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        Q09RestoreDrillSupport.registerContainerProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiAuthorizationService authorizationService;

    @Autowired
    private AiTicketService ticketService;

    @Autowired
    private AiKnowledgeBaseService knowledgeBaseService;

    @Autowired
    private AiKnowledgeDocumentService documentService;

    @Autowired
    private AiKnowledgeChunkService chunkService;

    @Autowired
    private AiKnowledgeIndexGenerationService generationService;

    @Autowired
    private AiReportService reportService;

    @Autowired
    private AiModelEndpointService modelEndpointService;

    @Autowired
    private AiFileService fileService;

    @Autowired
    private FileConfigService fileConfigService;

    @Autowired
    private CredentialCipher credentialCipher;

    // ===== 演练主流程 =====

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void backupDestroyAndRestoreDatabaseAndIndex() throws Exception {
        recordEnvironment();
        seedBusinessData();
        assertBusinessDataUsable("播种后（备份前）");

        // ---- 备份：mysqldump 导出整库到宿主机；记录体积与 SHA-256 ----
        preBackupTableCounts = tableRowCounts(jdbcTemplate);
        tBackupStartMillis = System.currentTimeMillis();
        ExecResult dump = MYSQL.execInContainer(
                "sh",
                "-c",
                "mysqldump -uroot -p\"$MYSQL_ROOT_PASSWORD\" --single-transaction --routines --events --triggers"
                        + " --hex-blob --set-gtid-purged=OFF basic_framework > /tmp/q09-drill-backup.sql"
                        + " 2>/tmp/q09-drill-backup.err; echo dump_exit=$?; wc -c < /tmp/q09-drill-backup.sql");
        assertThat(dump.getStdout()).as("mysqldump 必须成功：%s", dump.getStderr()).contains("dump_exit=0");
        tBackupEndMillis = System.currentTimeMillis();
        backupFile = Path.of(System.getProperty("java.io.tmpdir"), "q09-drill-backup.sql");
        MYSQL.copyFileFromContainer("/tmp/q09-drill-backup.sql", backupFile.toString());
        backupBytes = Files.size(backupFile);
        backupSha256 = sha256(backupFile);
        metric("backup_bytes_container_side", dump.getStdout().replaceAll("[^0-9]", ""));
        indexSnapshotName = createIndexSnapshot();
        indexSnapshotBytes = snapshotBytes();
        metric("backup_bytes", backupBytes);
        metric("backup_sha256", backupSha256);
        metric("backup_seconds", seconds(tBackupStartMillis, tBackupEndMillis));
        metric("backup_tables", preBackupTableCounts.size());
        metric("index_snapshot_name", indexSnapshotName);
        metric("index_snapshot_bytes", indexSnapshotBytes);

        // ---- RPO 探针：备份完成之后写入一条真实业务数据（恢复后应当丢失） ----
        tPostBackupWriteMillis = System.currentTimeMillis();
        grantService.createGrant(applicationId, "USER", BOB, "REPORT", REPORT_KEY, Set.of("READ"));

        // ---- 破坏：删除授权、知识文档版本与切片、报表版本内容，并清空向量索引 ----
        tDestroyStartMillis = System.currentTimeMillis();
        destroyedRows = 0;
        destroyedRows += jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE id = ?", aliceGrantId);
        destroyedRows += jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE id = ?", aliceKnowledgeGrantId);
        destroyedRows +=
                jdbcTemplate.update("DELETE FROM ai_knowledge_chunk WHERE document_version_id = ?", documentVersionId);
        destroyedRows +=
                jdbcTemplate.update("DELETE FROM ai_knowledge_document_version WHERE id = ?", documentVersionId);
        destroyedRows += jdbcTemplate.update("DELETE FROM ai_knowledge_document WHERE id = ?", documentId);
        destroyedRows += jdbcTemplate.update(
                "DELETE FROM ai_knowledge_index_generation WHERE knowledge_base_id = ?", knowledgeBaseId);
        destroyedRows += jdbcTemplate.update("DELETE FROM ai_knowledge_base WHERE id = ?", knowledgeBaseId);
        String filePath = jdbcTemplate.queryForObject("SELECT path FROM infra_file WHERE id = ?", String.class, fileId);
        destroyedRows += jdbcTemplate.update("DELETE FROM ai_file_binding WHERE file_id = ?", fileId);
        destroyedRows += jdbcTemplate.update(
                "DELETE FROM infra_file_content WHERE config_id = ? AND path = ?", fileConfigId, filePath);
        destroyedRows += jdbcTemplate.update("DELETE FROM infra_file WHERE id = ?", fileId);
        destroyedRows +=
                jdbcTemplate.update("DELETE FROM ai_model_endpoint_revision WHERE endpoint_id = ?", modelEndpointId);
        destroyedRows += jdbcTemplate.update("DELETE FROM ai_model_endpoint WHERE id = ?", modelEndpointId);
        destroyedRows += jdbcTemplate.update(
                "UPDATE ai_report_version SET data_json = ? WHERE report_id = ?", "{\"blocks\":[]}", reportId);
        indexPort.deleteAll(collectionName);
        tDestroyEndMillis = System.currentTimeMillis();
        long rpoWindowMillis = tDestroyStartMillis - tBackupEndMillis;
        metric("rpo_window_millis", rpoWindowMillis);
        metric("destroyed_rows", destroyedRows);
        metric("destroy_seconds", seconds(tDestroyStartMillis, tDestroyEndMillis));

        // 破坏必须真实可见（否则演练无效）
        loginAs(ALICE);
        assertThat(decision(ALICE)).as("授权行删除后判定必须拒绝").isFalse();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_knowledge_document_version WHERE id = ?",
                        Long.class,
                        documentVersionId))
                .as("版本行已被删除（服务层按 404 语义拒绝）")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_knowledge_chunk WHERE document_version_id = ?",
                        Long.class,
                        documentVersionId))
                .as("切片行已被删除")
                .isZero();
        // 失权后旧报表按 A03/R04 语义拒绝读取（AT-048），同时数据确实被改写
        assertThatThrownBy(() -> reportService.readCurrent(reportId))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED.getCode()));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT data_json FROM ai_report_version WHERE report_id = ? AND version_no = 1",
                        String.class,
                        reportId))
                .as("报表版本内容已被改写")
                .isEqualTo("{\"blocks\":[]}");
        assertThat(indexPort.search(collectionName, chunkVector(0), 5, knowledgeBaseFilter()))
                .isEmpty();

        // ---- 恢复 1：MySQL（备份文件 → 整库回放） ----
        MYSQL.copyFileToContainer(MountableFile.forHostPath(backupFile), "/tmp/q09-drill-restore.sql");
        tRestoreStartMillis = System.currentTimeMillis();
        ExecResult restore = MYSQL.execInContainer(
                "sh",
                "-c",
                "mysql -uroot -p\"$MYSQL_ROOT_PASSWORD\" --default-character-set=utf8mb4 basic_framework"
                        + " < /tmp/q09-drill-restore.sql; echo restore_exit=$?");
        tRestoreEndMillis = System.currentTimeMillis();
        assertThat(restore.getStdout()).as("恢复必须成功：%s", restore.getStderr()).contains("restore_exit=0");
        metric("mysql_restore_seconds", seconds(tRestoreStartMillis, tRestoreEndMillis));

        // 数据保真：全库表行数与备份前完全一致
        Map<String, Long> postRestoreTableCounts = tableRowCounts(jdbcTemplate);
        assertThat(postRestoreTableCounts).as("恢复后全库表行数必须与备份前一致").isEqualTo(preBackupTableCounts);

        // 业务内容真的回来了
        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM ai_resource_grant WHERE id IN (?, ?) AND status = 'ACTIVE'",
                        Long.class,
                        aliceGrantId,
                        aliceKnowledgeGrantId))
                .as("被删除的两条授权行必须恢复")
                .hasSize(2);
        assertThat(chunkService.listVersionChunks(documentVersionId)).hasSize(CHUNK_TEXTS.size());
        assertThat(reportService.readCurrent(reportId).getDataJson()).isEqualTo(ORIGINAL_REPORT_DATA);
        // 文件（DB 存储）：恢复后按 A07 语义读回原文（绑定与授权都在）
        loginAs(ALICE);
        assertThat(new String(fileService.read(fileId), StandardCharsets.UTF_8))
                .as("恢复后文件内容必须可读且一致")
                .isEqualTo(new String(FILE_CONTENT, StandardCharsets.UTF_8));

        // 密钥版本：恢复后的密文必须能用同一主密钥解开；换密钥版本则 fail-closed（解不开）
        String restoredCiphertext = jdbcTemplate.queryForObject(
                "SELECT credential_ciphertext FROM ai_model_endpoint WHERE id = ?", String.class, modelEndpointId);
        assertThat(restoredCiphertext).as("恢复后端点密文必须存在且为版本化密文").startsWith("v1.");
        assertThat(credentialCipher.decrypt(restoredCiphertext, "ai_model_endpoint:" + modelEndpointId))
                .as("同一密钥版本下恢复后的凭据必须可解")
                .isEqualTo(ENDPOINT_CREDENTIAL);
        SecurityProperties wrongKeyProperties = new SecurityProperties();
        wrongKeyProperties.setCredentialEncryptionKey(WRONG_KEY_BASE64);
        CredentialCipher wrongKeyCipher = new CredentialCipher(wrongKeyProperties);
        assertThatThrownBy(() -> wrongKeyCipher.decrypt(restoredCiphertext, "ai_model_endpoint:" + modelEndpointId))
                .as("不同密钥版本解不开恢复的密文（必须先恢复密钥版本）")
                .isInstanceOf(IllegalStateException.class);
        metric("key_version_verified", "same-key-decrypts/wrong-key-fails");

        // RPO：备份后写入的授权在恢复后不存在（该窗口的数据确实丢失）
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_resource_grant WHERE application_id = ? AND external_user_id = ?",
                        Long.class,
                        applicationId,
                        BOB))
                .as("备份完成之后写入的数据不在恢复点内（RPO 实测）")
                .isZero();

        // ---- 恢复 2：索引（DB 恢复不覆盖向量索引 → 必须靠快照恢复或重建） ----
        assertThat(indexPort.search(collectionName, chunkVector(0), 5, knowledgeBaseFilter()))
                .as("MySQL 恢复不会带回向量索引：快照未恢复前检索为空")
                .isEmpty();
        recoverIndexSnapshot();
        tIndexRecoverEndMillis = System.currentTimeMillis();
        metric("index_recover_seconds", seconds(tRestoreEndMillis, tIndexRecoverEndMillis));

        // 内容/ACL/版本正确（AT-030）
        List<KnowledgeIndexPort.SearchHit> hits =
                indexPort.search(collectionName, chunkVector(0), 5, knowledgeBaseFilter());
        assertThat(hits)
                .extracting(KnowledgeIndexPort.SearchHit::id)
                .containsExactlyInAnyOrderElementsOf(expectedVectorIds());
        assertThat(hits).allSatisfy(hit -> {
            assertThat(hit.payload()).containsEntry("knowledge_base_id", String.valueOf(knowledgeBaseId));
            assertThat(hit.payload()).containsEntry("document_version_id", String.valueOf(documentVersionId));
            assertThat(hit.payload())
                    .containsEntry(
                            "text",
                            CHUNK_TEXTS.get(Integer.parseInt(
                                    String.valueOf(hit.payload().get("chunk_index")))));
        });
        assertThat(indexPort.search(collectionName, chunkVector(0), 10, null))
                .as("未过滤检索包含异租户点，证明过滤确实在服务端执行而不是数据本身只有两条")
                .hasSize(CHUNK_TEXTS.size() + 1);

        // 授权链路在恢复后（同一上下文）立即可用
        assertThat(decision(ALICE)).isTrue();
        assertThat(decision(BOB)).as("RPO 窗口内新增的授权不存在").isFalse();

        metric("restored_index_points", expectedVectorIds().size());
        metric("db_restore_point_epoch_millis", tRestoreEndMillis);
        System.out.println("Q09-DRILL-PHASE-1-OK " + metricsSoFar());
    }

    /**
     * 应用重启（新 Spring 上下文）后，仅凭恢复的数据验证业务能力：权限、引用、报表、文件、索引。
     * 这正是"恢复后跑授权/引用/报表用例"的落点。
     */
    @Test
    @Order(2)
    void businessCapabilitiesAreUsableAfterRestartOnRestoredDatabase() {
        // A01：恢复后的凭据仍可认证（凭据摘要与加密密钥版本都随备份/配置一致）
        assertThat(applicationService.authenticate(APP_CODE, applicationSecret))
                .as("恢复后应用与凭据必须可用")
                .isNotNull();

        // A03 授权：恢复的授权放行、RPO 窗口丢失的授权拒绝
        loginAs(ALICE);
        assertThat(decision(ALICE)).as("恢复后的授权必须放行").isTrue();
        assertThat(decision(BOB)).as("备份后写入的授权在恢复点外，必须拒绝").isFalse();

        // K02 引用：active 版本、切片与位置引用完整
        assertThat(documentService.getActiveVersion(documentId).getId())
                .as("恢复后 active 版本必须与备份时一致")
                .isEqualTo(documentVersionId);
        var chunks = chunkService.listVersionChunks(documentVersionId);
        assertThat(chunks).hasSize(CHUNK_TEXTS.size());
        assertThat(chunks)
                .extracting(chunk -> chunk.getVectorId())
                .containsExactlyInAnyOrderElementsOf(expectedVectorIds());

        // R04 报表：版本内容与范围指纹复核通过（切回 alice 会话）
        loginAs(ALICE);
        assertThat(reportService.readCurrent(reportId).getVersionNo()).isEqualTo(1);
        assertThat(reportService.readCurrent(reportId).getDataJson()).isEqualTo(ORIGINAL_REPORT_DATA);

        // 文件（DB 存储）：重启后按 A07 语义仍可读回原文
        loginAs(ALICE);
        assertThat(new String(fileService.read(fileId), StandardCharsets.UTF_8))
                .as("重启后恢复的文件必须仍可读")
                .isEqualTo(new String(FILE_CONTENT, StandardCharsets.UTF_8));

        // 密钥版本：新上下文（同一密钥配置）下恢复的凭据仍可解
        String ciphertextAfterRestart = jdbcTemplate.queryForObject(
                "SELECT credential_ciphertext FROM ai_model_endpoint WHERE id = ?", String.class, modelEndpointId);
        assertThat(credentialCipher.decrypt(ciphertextAfterRestart, "ai_model_endpoint:" + modelEndpointId))
                .as("重启后同一密钥版本仍可解开恢复的凭据")
                .isEqualTo(ENDPOINT_CREDENTIAL);

        // 索引：快照恢复后的向量仍可检索，且载荷指向恢复后的版本
        List<KnowledgeIndexPort.SearchHit> hits =
                indexPort.search(collectionName, chunkVector(1), 5, knowledgeBaseFilter());
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).payload()).containsEntry("document_version_id", String.valueOf(documentVersionId));

        tVerifiedEndMillis = System.currentTimeMillis();
        metric("rto_total_seconds", seconds(tDestroyStartMillis, tVerifiedEndMillis));
        metric("rto_mysql_restore_seconds", seconds(tRestoreStartMillis, tRestoreEndMillis));
        metric("rto_restore_to_verified_seconds", seconds(tRestoreStartMillis, tVerifiedEndMillis));
        metric("verified_after_restart_epoch_millis", tVerifiedEndMillis);
        String summary = metricsSoFar();
        System.out.println("Q09-DRILL-PHASE-2-OK " + summary);
        System.out.println("Q09-DRILL-METRICS-BEGIN");
        System.out.println(summary);
        System.out.println("Q09-DRILL-METRICS-END");
    }

    // ===== 播种（全部走真实服务，事务已提交） =====

    private void seedBusinessData() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("Q09 恢复演练应用")
                .setOrigins(List.of("https://q09.example.com")));
        applicationId = issue.getApplication().getId();
        applicationSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, ALICE, ALICE, "crm-auth", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, BOB, BOB, "crm-auth", 1L);
        aliceGrantId = grantService.createGrant(applicationId, "USER", ALICE, "REPORT", REPORT_KEY, Set.of("READ"));
        aliceKnowledgeGrantId =
                grantService.createGrant(applicationId, "USER", ALICE, "KNOWLEDGE_BASE", "kb-1", Set.of("READ"));

        ensureMasterFileConfig();
        loginAs(ALICE);
        fileId = fileService
                .upload("ai_knowledge_document", "kb-1", "q09-handbook.txt", "text/plain", FILE_CONTENT)
                .getFileId();

        knowledgeBaseId = knowledgeBaseService.create(new AiKnowledgeBaseSaveDTO()
                .setCode(KNOWLEDGE_BASE_CODE)
                .setName("Q09 演练知识库")
                .setVisibility(AiKnowledgeBaseDO.VISIBILITY_SHARED)
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(EMBEDDING_DIMENSION)
                .setRetentionDays(180));
        AiKnowledgeDocumentUpsertResultDTO upsert = documentService.upsert(new AiKnowledgeDocumentSaveDTO()
                .setKnowledgeBaseId(knowledgeBaseId)
                .setSourceKey("handbook/q09.txt")
                .setTitle("Q09 演练文档")
                .setSourceType("API_SYNC")
                .setSourceRef("drive:handbook/q09.txt")
                .setFileId(fileId)
                .setContentHash("a".repeat(64)));
        documentId = upsert.getDocumentId();
        documentVersionId = upsert.getVersionId();
        generationNo = generationService.startGeneration(knowledgeBaseId);
        List<AiKnowledgeChunkDTO> chunkRows = new ArrayList<>();
        for (int index = 0; index < CHUNK_TEXTS.size(); index++) {
            chunkRows.add(new AiKnowledgeChunkDTO()
                    .setChunkIndex(index)
                    .setContentHash("c".repeat(64))
                    .setTextLength(CHUNK_TEXTS.get(index).length())
                    .setTokenCount(32)
                    .setVectorId(AiKnowledgeChunker.deterministicVectorId(documentVersionId, index))
                    .setLocationRef("段落 " + (index + 1)));
        }
        chunkService.replaceVersionChunks(documentVersionId, generationNo, chunkRows);
        generationService.activate(knowledgeBaseId, generationNo);
        documentService.markVersionReady(documentId, documentVersionId, generationNo);
        AiKnowledgeIndexGenerationDO generation = generationService.getGeneration(knowledgeBaseId, generationNo);
        collectionName = generation.getCollectionName();
        assertThat(collectionName).isEqualTo("kb_" + KNOWLEDGE_BASE_CODE + "_g" + generationNo);

        // 向量索引：载荷与 K05 生产写入一致（knowledge_base_id/document_version_id/chunk_index/location_ref/text）
        indexPort = new QdrantRestKnowledgeIndexAdapter(
                Q09RestoreDrillSupport.qdrantBaseUrl(), QDRANT_API_KEY, Duration.ofSeconds(20), true);
        indexPort.ensureCollection(collectionName, EMBEDDING_DIMENSION);
        List<KnowledgeIndexPort.IndexDocument> points = new ArrayList<>();
        for (int index = 0; index < CHUNK_TEXTS.size(); index++) {
            points.add(new KnowledgeIndexPort.IndexDocument(
                    AiKnowledgeChunker.deterministicVectorId(documentVersionId, index),
                    chunkVector(index),
                    Map.of(
                            "knowledge_base_id", String.valueOf(knowledgeBaseId),
                            "document_version_id", String.valueOf(documentVersionId),
                            "chunk_index", String.valueOf(index),
                            "location_ref", "段落 " + (index + 1),
                            "text", CHUNK_TEXTS.get(index))));
        }
        // 异租户反例：相同向量、不同知识库，用于证明服务端 ACL 过滤在恢复后仍生效
        points.add(new KnowledgeIndexPort.IndexDocument(
                "q09-foreign-tenant",
                chunkVector(0),
                Map.of(
                        "knowledge_base_id", "999999",
                        "document_version_id", "999999",
                        "chunk_index", "0",
                        "location_ref", "段落 1",
                        "text", "另一个知识库的文档，不应被本库检索命中。")));
        indexPort.upsert(collectionName, points);

        // 模型端点：凭据以 AES-GCM 密文落库（密钥来自配置，不随数据库备份走）
        modelEndpointId = modelEndpointService.createEndpoint(new AiModelEndpointSaveDTO()
                .setName("Q09 演练端点")
                .setProvider("openai")
                .setBaseUrl("https://api.q09.example.com/v1")
                .setModelId("q09-chat-model")
                .setCapabilities(List.of("TEXT"))
                .setCredential(ENDPOINT_CREDENTIAL));

        // 报表：真实 run 依赖链 + R04 保存（归属与范围指纹来自会话身份）
        prepareReportRunChain();
        loginAs(ALICE);
        reportId = reportService.create(new AiReportSaveDTO()
                .setCode(REPORT_CODE)
                .setName("Q09 销售总览")
                .setDescription("Q09 恢复演练")
                .setMode(AiReportDO.MODE_SNAPSHOT)
                .setSchemaVersion("1.0")
                .setSpecJson("{\"schemaVersion\":\"1.0\"}")
                .setDataJson(ORIGINAL_REPORT_DATA)
                .setSourcesJson(
                        "[{\"resourceType\":\"KNOWLEDGE_BASE\",\"resourceKey\":\"kb-1\",\"resultRef\":\"res_1\"}]")
                .setCompleteness("COMPLETE")
                .setCreatedByRun(RUN_KEY));
    }

    private void prepareReportRunChain() {
        jdbcTemplate.update(
                "INSERT INTO ai_service (app_id, code, name, status, model_endpoint_id, prompt_template,"
                        + " input_schema, required_capabilities, run_subject_type, creator, updater)"
                        + " VALUES (?, 'it-q09-service', 'Q09 服务', 'READY', 1, 'prompt', '{}', 'TEXT', 'USER', 'q09', 'q09')",
                applicationId);
        Long serviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_service WHERE code = 'it-q09-service' AND app_id = ?", Long.class, applicationId);
        jdbcTemplate.update(
                "INSERT INTO ai_service_release (service_id, model_endpoint_id, endpoint_config_revision,"
                        + " prompt_template, input_schema, required_capabilities, content_hash, status, release_version,"
                        + " creator, updater)"
                        + " VALUES (?, 1, 1, 'prompt', '{}', 'TEXT', ?, 'ACTIVE', 1, 'q09', 'q09')",
                serviceId,
                "b".repeat(64));
        Long releaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_service_release WHERE service_id = ?", Long.class, serviceId);
        jdbcTemplate.update(
                "INSERT INTO ai_conversation (application_id, subject_type, external_user_id, conversation_key,"
                        + " title, business_context, creator, updater)"
                        + " VALUES (?, 'USER', ?, 'conv_q09_drill', 'Q09 会话', '{}', 'q09', 'q09')",
                applicationId,
                ALICE);
        Long conversationId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_conversation WHERE conversation_key = 'conv_q09_drill'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO ai_run (run_key, application_id, subject_type, external_user_id, conversation_id,"
                        + " service_id, release_id, model_endpoint_id, endpoint_config_revision, content_hash,"
                        + " input_digest, status, step_count, creator, updater)"
                        + " VALUES (?, ?, 'USER', ?, ?, ?, ?, 1, 1, ?, ?, 'RUNNING', 0, 'q09', 'q09')",
                RUN_KEY,
                applicationId,
                ALICE,
                conversationId,
                serviceId,
                releaseId,
                "c".repeat(64),
                "d".repeat(64));
    }

    // ===== 断言辅助 =====

    /** 集成环境前置：数据库存储的文件主配置（A07 上传/读取走 getMasterFileClient）。 */
    private void ensureMasterFileConfig() {
        if (fileConfigId != null) {
            return;
        }
        fileConfigId = fileConfigService.createFileConfig(
                new FileConfigDO()
                        .setName("it-q09-file-" + System.nanoTime())
                        .setStorage(FileStorageEnum.DB.getStorage()),
                Map.of("domain", "http://localhost/files"));
        fileConfigService.updateFileConfigMaster(fileConfigId);
    }

    private void assertBusinessDataUsable(String phase) {
        loginAs(ALICE);
        assertThat(decision(ALICE)).as("%s：授权可用", phase).isTrue();
        assertThat(documentService.getActiveVersion(documentId).getId())
                .as("%s：active 版本可用", phase)
                .isEqualTo(documentVersionId);
        assertThat(chunkService.listVersionChunks(documentVersionId))
                .as("%s：切片引用可用", phase)
                .hasSize(CHUNK_TEXTS.size());
        assertThat(reportService.readCurrent(reportId).getDataJson())
                .as("%s：报表版本可读", phase)
                .isEqualTo(ORIGINAL_REPORT_DATA);
        assertThat(indexPort.search(collectionName, chunkVector(0), 5, knowledgeBaseFilter()))
                .as("%s：索引可检索", phase)
                .hasSize(CHUNK_TEXTS.size());
    }

    private boolean decision(String subject) {
        loginAs(subject);
        return authorizationService
                .authorize(
                        applicationId,
                        "USER",
                        subject,
                        AiResourceType.REPORT,
                        REPORT_KEY,
                        AiAction.READ,
                        List.of(REPORT_KEY))
                .isAllowed();
    }

    private void loginAs(String subject) {
        var ticket = ticketService.issue(APP_CODE, applicationSecret, AiSubjectType.USER, subject, List.of("kb-1"));
        LoginUser loginUser = new LoginUser()
                .setId(ticketService.verify(ticket.getToken()).getTicketId())
                .setUserType(UserTypeEnum.MEMBER.getValue())
                .setInfo(Map.of(
                        AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID,
                        String.valueOf(applicationId),
                        AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE,
                        "USER",
                        AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID,
                        subject,
                        AiUserSessionCommonApi.INFO_KEY_SCOPE_FINGERPRINT,
                        ticket.getScopeFingerprint()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
}
