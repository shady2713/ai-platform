package com.basicframework.module.ai.service.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.file.AiFileBindingDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.mysql.file.AiFileBindingMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionRequestDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionResultDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionTaskLeaseDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** K03 入库服务：归属校验、幂等、补偿、租约栅栏与人工重试规则。 */
class AiKnowledgeIngestionServiceImplTest {

    private static final Long BASE_ID = 61L;

    private static final Long FILE_ID = 501L;

    private static final String HASH = "a".repeat(64);

    private final AiKnowledgeIngestionTaskMapper taskMapper = mock(AiKnowledgeIngestionTaskMapper.class);

    private final AiKnowledgeBaseMapper baseMapper = mock(AiKnowledgeBaseMapper.class);

    private final AiFileBindingMapper fileBindingMapper = mock(AiFileBindingMapper.class);

    private final AiFileService fileService = mock(AiFileService.class);

    private final AiKnowledgeDocumentService documentService = mock(AiKnowledgeDocumentService.class);

    private final AiKnowledgeIngestionWriter writer = mock(AiKnowledgeIngestionWriter.class);

    private final AiKnowledgeIngestionServiceImpl service = new AiKnowledgeIngestionServiceImpl(
            taskMapper, writer, baseMapper, fileBindingMapper, fileService, documentService);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiKnowledgeIngestionRequestDTO request() {
        return new AiKnowledgeIngestionRequestDTO()
                .setKnowledgeBaseId(BASE_ID)
                .setSourceKey("handbook/v1.pdf")
                .setTitle("员工手册")
                .setSourceType("UPLOAD")
                .setFileId(FILE_ID)
                .setContentHash(HASH);
    }

    private void stubBaseAndFile(String businessKey) {
        when(baseMapper.selectById(BASE_ID))
                .thenReturn(new AiKnowledgeBaseDO()
                        .setId(BASE_ID)
                        .setCode("handbook")
                        .setStatus("ENABLED"));
        when(fileBindingMapper.selectActiveByFile(FILE_ID))
                .thenReturn(List.of(new AiFileBindingDO()
                        .setFileId(FILE_ID)
                        .setBusinessType("ai_knowledge_document")
                        .setBusinessKey(businessKey)
                        .setStatus(AiFileBindingDO.STATUS_ACTIVE)));
    }

    @Test
    void ingestCreatesVersionAndTaskInOneCall() {
        stubBaseAndFile("handbook");
        when(writer.createVersionAndTask(any(), any()))
                .thenReturn(new AiKnowledgeIngestionResultDTO()
                        .setDocumentId(71L)
                        .setVersionId(81L)
                        .setVersionNo(1)
                        .setTaskId(91L)
                        .setReused(false)
                        .setCreatedVersion(true));

        AiKnowledgeIngestionResultDTO result = service.ingest(request());

        assertThat(result.getTaskId()).isEqualTo(91L);
        assertThat(result.getVersionNo()).isEqualTo(1);
        verify(fileService, never()).release(any());
    }

    @Test
    void ingestRejectsFileOwnedByAnotherKnowledgeBaseOrUnknownBase() {
        stubBaseAndFile("other-base");
        assertThatThrownBy(() -> service.ingest(request()))
                .as("上传到别的知识库的文件不能挂到本库")
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID));
        // 归属校验失败也要补偿（文件已被上传，不能留悬空引用）
        verify(fileService).release(FILE_ID);

        when(baseMapper.selectById(BASE_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.ingest(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID));
        assertThatThrownBy(() -> service.ingest(request().setFileId(null)))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID));
        assertThatThrownBy(() -> service.ingest(null))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID));
    }

    @Test
    void ingestCompensatesFileWhenVersionOrTaskCreationFails() {
        stubBaseAndFile("handbook");
        when(writer.createVersionAndTask(any(), any()))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_STATE_CONFLICT));

        assertThatThrownBy(() -> service.ingest(request()))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_STATE_CONFLICT));
        verify(fileService).release(FILE_ID);
    }

    @Test
    void claimSkipsTasksTakenByOtherWorkersAndBuildsLeases() {
        LocalDateTime now = LocalDateTime.now();
        when(taskMapper.selectClaimable(any(), anyInt()))
                .thenReturn(List.of(
                        new AiKnowledgeIngestionTaskDO().setId(91L).setDocumentVersionId(81L),
                        new AiKnowledgeIngestionTaskDO().setId(92L).setDocumentVersionId(82L)));
        when(taskMapper.claim(eq(91L), anyString(), any(), any())).thenReturn(0);
        when(taskMapper.claim(eq(92L), anyString(), any(), any())).thenReturn(1);
        when(taskMapper.selectById(92L))
                .thenReturn(new AiKnowledgeIngestionTaskDO()
                        .setId(92L)
                        .setKnowledgeBaseId(BASE_ID)
                        .setDocumentId(71L)
                        .setDocumentVersionId(82L)
                        .setTaskKind(AiKnowledgeIngestionTaskDO.KIND_PARSE)
                        .setClaimedEpoch(2));

        List<AiKnowledgeIngestionTaskLeaseDTO> leases = service.claim("worker-1", 5, 60);

        assertThat(leases).hasSize(1);
        assertThat(leases.get(0).taskId()).isEqualTo(92L);
        assertThat(leases.get(0).epoch()).isEqualTo(2);
        assertThat(leases.get(0).owner()).isEqualTo("worker-1");
        assertThat(leases.get(0).leaseExpiresTime()).isAfter(now);

        assertThatThrownBy(() -> service.claim("  ", 5, 60))
                .satisfies(throwable ->
                        assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_INGESTION_TASK_STATE_INVALID));
    }

    @Test
    void heartbeatAndFinishUseTheLeaseFence() {
        AiKnowledgeIngestionTaskLeaseDTO lease = new AiKnowledgeIngestionTaskLeaseDTO(
                91L, BASE_ID, 71L, 81L, "PARSE", "worker-1", 3, LocalDateTime.now());
        when(taskMapper.heartbeat(eq(91L), eq("worker-1"), eq(3), any(), any())).thenReturn(1);
        when(taskMapper.finish(eq(91L), eq("worker-1"), eq(3), eq("SUCCEEDED"), any()))
                .thenReturn(1);

        assertThat(service.heartbeat(lease, 60)).isTrue();
        assertThat(service.finish(lease, AiKnowledgeIngestionTaskDO.STATUS_SUCCEEDED, null))
                .isTrue();
        assertThat(service.heartbeat(null, 60)).isFalse();

        when(taskMapper.finish(eq(91L), eq("worker-1"), eq(3), eq("FAILED"), any()))
                .thenReturn(0);
        assertThat(service.finish(lease, AiKnowledgeIngestionTaskDO.STATUS_FAILED, "parser-unavailable\n第二行"))
                .as("栅栏未命中（租约被接管）时结果不算数")
                .isFalse();

        assertThatThrownBy(() -> service.finish(lease, "RUNNING", null))
                .as("非终态不允许写入")
                .satisfies(throwable ->
                        assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_INGESTION_TASK_STATE_INVALID));
    }

    @Test
    void retryOnlyAcceptsFailedOrUnknownTasks() {
        when(taskMapper.selectById(91L))
                .thenReturn(new AiKnowledgeIngestionTaskDO()
                        .setId(91L)
                        .setStatus(AiKnowledgeIngestionTaskDO.STATUS_FAILED)
                        .setVersion(4));
        when(taskMapper.requeue(eq(91L), eq(4), any())).thenReturn(1);
        service.retry(91L, 4);
        verify(taskMapper).requeue(eq(91L), eq(4), any());

        when(taskMapper.selectById(91L))
                .thenReturn(new AiKnowledgeIngestionTaskDO()
                        .setId(91L)
                        .setStatus(AiKnowledgeIngestionTaskDO.STATUS_RUNNING)
                        .setVersion(4));
        assertThatThrownBy(() -> service.retry(91L, 4))
                .as("执行中的任务不接受人工重试")
                .satisfies(throwable ->
                        assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_INGESTION_TASK_STATE_INVALID));

        when(taskMapper.selectById(91L))
                .thenReturn(new AiKnowledgeIngestionTaskDO()
                        .setId(91L)
                        .setStatus(AiKnowledgeIngestionTaskDO.STATUS_FAILED)
                        .setVersion(4));
        when(taskMapper.requeue(eq(91L), eq(4), any())).thenReturn(0);
        assertThatThrownBy(() -> service.retry(91L, 4))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_STATE_CONFLICT));

        when(taskMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.getTask(404L))
                .satisfies(
                        throwable -> assertCode(throwable, AiErrorCodeConstants.AI_KNOWLEDGE_INGESTION_TASK_NOT_FOUND));
    }

    @Test
    void recoveryAndPagingDelegateToMapper() {
        when(taskMapper.recoverExpired(any(), any(), anyInt())).thenReturn(2);

        assertThat(service.recoverExpiredLeases(30, 200)).isEqualTo(2);
        verify(taskMapper).recoverExpired(any(), any(), eq(200));

        service.getTaskPage(new com.basicframework.framework.common.pojo.PageParam(), BASE_ID, "FAILED");
        verify(taskMapper).selectPage(any(), eq(BASE_ID), eq("FAILED"));
    }
}
