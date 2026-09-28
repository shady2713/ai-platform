package com.basicframework.module.ai.service.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.adapter.file.AiFileSubjectResolver;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaAssetDO;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.mysql.media.AiMediaAssetMapper;
import com.basicframework.module.ai.dal.mysql.media.AiMediaTaskMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.file.dto.AiFileSubject;
import com.basicframework.module.ai.service.media.dto.AiMediaStepOutcome;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskLeaseDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskSubmitDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 媒体任务服务（X03）：受理即落库（固定端点与配置版本）、幂等复用与冲突拒绝、主体范围隔离、
 * 取消只对未开始的任务生效、领取有界且跳过被抢先行、终态栅栏与用量如实。
 *
 * <p>断言以"服务对外承诺的事实"为准：落库行的取值、被调用的 Mapper 参数、返回视图的字段；
 * 不核对实现细节。
 */
@ExtendWith(MockitoExtension.class)
class AiMediaTaskServiceImplTest {

    private static final Long APPLICATION_ID = 11L;

    private static final Long ENDPOINT_ID = 22L;

    private static final String WORKER = "media-task-abc12345";

    @Mock
    private AiMediaTaskMapper taskMapper;

    @Mock
    private AiMediaAssetMapper assetMapper;

    @Mock
    private AiFileSubjectResolver subjectResolver;

    @Mock
    private AiModelEndpointService endpointService;

    private AiMediaTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiMediaTaskServiceImpl(taskMapper, assetMapper, subjectResolver, endpointService);
    }

    @Test
    void submitWritesQueuedTaskWithFixedEndpointRevisionModelAndUnknownUsage() {
        loginAs("alice");
        when(taskMapper.selectByRequestKey(APPLICATION_ID, "USER", "alice", "img-1"))
                .thenReturn(null);
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID))
                .thenReturn(new AiModelEndpointDO().setId(ENDPOINT_ID).setConfigRevision(3));
        when(endpointService.getRevisions(ENDPOINT_ID))
                .thenReturn(List.of(new AiModelEndpointRevisionDO().setModelId("gpt-image-1")));
        when(taskMapper.insert(any(AiMediaTaskDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiMediaTaskDO.class).setId(501L);
            return 1;
        });

        AiMediaTaskResultDTO result = service.submit(generateRequest("img-1", "一只坐着的橘猫"));

        ArgumentCaptor<AiMediaTaskDO> captor = ArgumentCaptor.forClass(AiMediaTaskDO.class);
        verify(taskMapper).insert(captor.capture());
        AiMediaTaskDO inserted = captor.getValue();
        assertThat(inserted.getStatus()).as("受理即待领取").isEqualTo(AiMediaTaskDO.STATUS_QUEUED);
        assertThat(inserted.getEndpointId()).isEqualTo(ENDPOINT_ID);
        assertThat(inserted.getEndpointConfigRevision()).as("受理时固定配置版本").isEqualTo(3);
        assertThat(inserted.getModelRef()).as("受理时固定模型标识").isEqualTo("gpt-image-1");
        assertThat(inserted.getApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(inserted.getSubjectType()).isEqualTo("USER");
        assertThat(inserted.getExternalUserId()).isEqualTo("alice");
        assertThat(inserted.getUsageSource()).as("上游尚未调用时用量来源为 UNKNOWN").isEqualTo(AiMediaTaskDO.USAGE_SOURCE_UNKNOWN);
        assertThat(inserted.getUsageQuantity()).as("未知用量不写 0").isNull();
        assertThat(inserted.getUsageUnit()).isNull();
        assertThat(inserted.getResultCount()).isZero();
        assertThat(inserted.getAttemptCount()).isZero();
        assertThat(inserted.getMaxAttempts()).isEqualTo(3);
        assertThat(inserted.getClaimedEpoch()).isZero();
        assertThat(inserted.getNextAttemptTime())
                .as("可领取时间按秒截断：datetime(0) 会把亚秒值向上取整成未来 1 秒")
                .isNotNull();
        assertThat(inserted.getNextAttemptTime().getNano()).isZero();
        assertThat(result.getId()).as("受理返回落库后的任务编号").isEqualTo(501L);
        assertThat(result.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_QUEUED);
        assertThat(result.getAssets()).isEmpty();
    }

    @Test
    void submitDefaultsMissingOutputCountToOne() {
        loginAs("alice");
        when(taskMapper.selectByRequestKey(APPLICATION_ID, "USER", "alice", "img-count"))
                .thenReturn(null);
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID))
                .thenReturn(new AiModelEndpointDO().setId(ENDPOINT_ID).setConfigRevision(3));
        when(endpointService.getRevisions(ENDPOINT_ID))
                .thenReturn(List.of(new AiModelEndpointRevisionDO().setModelId("gpt-image-1")));

        service.submit(generateRequest("img-count", "猫").setOutputCount(null));

        ArgumentCaptor<AiMediaTaskDO> captor = ArgumentCaptor.forClass(AiMediaTaskDO.class);
        verify(taskMapper).insert(captor.capture());
        assertThat(captor.getValue().getOutputCount()).isEqualTo(1);
    }

    @Test
    void repeatingSameKeyReturnsExistingTaskWithoutSecondInsertOrEndpointLookup() {
        loginAs("alice");
        AiMediaTaskDO existing = new AiMediaTaskDO()
                .setRequestKey("img-1")
                .setMediaKind(AiMediaTaskDO.KIND_IMAGE)
                .setOperation(AiMediaTaskDO.OPERATION_GENERATE)
                .setCapability("IMAGE_GENERATION")
                .setEndpointId(ENDPOINT_ID)
                .setInputText("一只坐着的橘猫")
                .setTargetSize("1024x1024")
                .setOutputCount(1)
                .setOutputFormat("png");
        AiMediaTaskDO owned = new AiMediaTaskDO()
                .setId(77L)
                .setRequestKey("img-1")
                .setApplicationId(APPLICATION_ID)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setMediaKind(existing.getMediaKind())
                .setOperation(existing.getOperation())
                .setCapability(existing.getCapability())
                .setEndpointId(ENDPOINT_ID)
                .setInputText(existing.getInputText())
                .setTargetSize(existing.getTargetSize())
                .setOutputCount(1)
                .setOutputFormat(existing.getOutputFormat())
                .setStatus(AiMediaTaskDO.STATUS_RUNNING);
        when(taskMapper.selectByRequestKey(APPLICATION_ID, "USER", "alice", "img-1"))
                .thenReturn(owned);
        when(assetMapper.selectByTask(77L)).thenReturn(List.of());

        AiMediaTaskResultDTO result = service.submit(generateRequest("img-1", "一只坐着的橘猫"));

        assertThat(result.getId()).as("重复提交返回既有任务").isEqualTo(77L);
        assertThat(result.getStatus()).as("返回既有任务当前事实").isEqualTo(AiMediaTaskDO.STATUS_RUNNING);
        verify(taskMapper, never()).insert(any(AiMediaTaskDO.class));
        verify(endpointService, never()).getEnabledEndpoint(anyLong());
    }

    @Test
    void sameKeyWithDifferentParamsIsRejectedAsIdempotencyConflict() {
        loginAs("alice");
        AiMediaTaskDO existing = new AiMediaTaskDO()
                .setId(77L)
                .setApplicationId(APPLICATION_ID)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setMediaKind(AiMediaTaskDO.KIND_IMAGE)
                .setOperation(AiMediaTaskDO.OPERATION_GENERATE)
                .setCapability("IMAGE_GENERATION")
                .setEndpointId(ENDPOINT_ID)
                .setInputText("另一只猫")
                .setOutputCount(1)
                .setOutputFormat("png");
        when(taskMapper.selectByRequestKey(APPLICATION_ID, "USER", "alice", "img-1"))
                .thenReturn(existing);

        assertThatThrownBy(() -> service.submit(generateRequest("img-1", "一只坐着的橘猫")))
                .isInstanceOfSatisfying(ServiceException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT.getCode());
                    assertThat(exception.getMessage()).contains("幂等键冲突");
                });
        verify(taskMapper, never()).insert(any(AiMediaTaskDO.class));
    }

    @Test
    void concurrentInsertLoserReusesTheRowThatWonTheUniqueKey() {
        loginAs("alice");
        AiMediaTaskDO winner = new AiMediaTaskDO()
                .setId(88L)
                .setApplicationId(APPLICATION_ID)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setMediaKind(AiMediaTaskDO.KIND_IMAGE)
                .setOperation(AiMediaTaskDO.OPERATION_GENERATE)
                .setCapability("IMAGE_GENERATION")
                .setEndpointId(ENDPOINT_ID)
                .setInputText("一只坐着的橘猫")
                .setTargetSize("1024x1024")
                .setOutputCount(1)
                .setOutputFormat("png")
                .setStatus(AiMediaTaskDO.STATUS_QUEUED);
        when(taskMapper.selectByRequestKey(APPLICATION_ID, "USER", "alice", "img-1"))
                .thenReturn(null, winner);
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID))
                .thenReturn(new AiModelEndpointDO().setId(ENDPOINT_ID).setConfigRevision(3));
        when(endpointService.getRevisions(ENDPOINT_ID))
                .thenReturn(List.of(new AiModelEndpointRevisionDO().setModelId("gpt-image-1")));
        when(taskMapper.insert(any(AiMediaTaskDO.class)))
                .thenThrow(new DuplicateKeyException("uk_ai_media_task_request"));
        when(assetMapper.selectByTask(88L)).thenReturn(List.of());

        assertThat(service.submit(generateRequest("img-1", "一只坐着的橘猫")).getId())
                .as("并发同键的输家复用赢家的事实")
                .isEqualTo(88L);
    }

    @Test
    void concurrentInsertLoserFailsWhenTheWinningRowIsNotReadable() {
        loginAs("alice");
        when(taskMapper.selectByRequestKey(APPLICATION_ID, "USER", "alice", "img-1"))
                .thenReturn(null, null);
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID))
                .thenReturn(new AiModelEndpointDO().setId(ENDPOINT_ID).setConfigRevision(3));
        when(endpointService.getRevisions(ENDPOINT_ID))
                .thenReturn(List.of(new AiModelEndpointRevisionDO().setModelId("gpt-image-1")));
        when(taskMapper.insert(any(AiMediaTaskDO.class)))
                .thenThrow(new DuplicateKeyException("uk_ai_media_task_request"));

        assertThatThrownBy(() -> service.submit(generateRequest("img-1", "一只坐着的橘猫")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void submitRejectsIllegalShapeBeforeTouchingTheSubject() {
        assertThatThrownBy(() -> service.submit(generateRequest("img-1", "猫").setEndpointId(null)))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));
        assertThatThrownBy(() -> service.submit(generateRequest("   ", "猫"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.submit(generateRequest("k".repeat(41), "猫")))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));
        assertThatThrownBy(() -> service.submit(generateRequest("img-1", "猫".repeat(2001))))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_REQUEST_INVALID.getCode()));
        assertThatThrownBy(() -> service.submit(generateRequest("img-1", "猫").setSourceFileId(0L)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.submit(null)).isInstanceOf(ServiceException.class);
        verify(subjectResolver, never()).resolveCurrent();
    }

    @Test
    void missingSubjectScopeIsRejectedForSubmitAndLookup() {
        when(subjectResolver.resolveCurrent()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(generateRequest("img-1", "猫")))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND.getCode()));
        assertThatThrownBy(() -> service.getTask(1L))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND.getCode()));
        assertThatThrownBy(() -> service.getAssets(1L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.cancel(1L)).isInstanceOf(ServiceException.class);
    }

    @Test
    void taskLookupIsScopedToCurrentSubjectAndHidesForeignRows() {
        loginAs("alice");
        when(taskMapper.selectById(9L))
                .thenReturn(new AiMediaTaskDO()
                        .setId(9L)
                        .setApplicationId(APPLICATION_ID)
                        .setSubjectType("USER")
                        .setExternalUserId("bob"));

        assertThatThrownBy(() -> service.getTask(9L)).isInstanceOfSatisfying(ServiceException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND.getCode());
            assertThat(exception.getMessage()).contains("资源不存在");
        });
    }

    @Test
    void unknownTaskIdIsAlsoNotFound() {
        loginAs("alice");
        when(taskMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getTask(999L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.getTask(null)).isInstanceOf(ServiceException.class);
    }

    @Test
    void getTaskReturnsTaskWithAssetsInOrdinalOrder() {
        loginAs("alice");
        when(taskMapper.selectById(7L)).thenReturn(ownedTask(7L, AiMediaTaskDO.STATUS_SUCCEEDED));
        when(assetMapper.selectByTask(7L))
                .thenReturn(List.of(
                        new AiMediaAssetDO()
                                .setTaskId(7L)
                                .setOrdinal(1)
                                .setFileId(1001L)
                                .setMimeType("image/png"),
                        new AiMediaAssetDO()
                                .setTaskId(7L)
                                .setOrdinal(2)
                                .setFileId(1002L)
                                .setMimeType("image/png")));

        AiMediaTaskResultDTO result = service.getTask(7L);

        assertThat(result.getId()).isEqualTo(7L);
        assertThat(result.getAssets()).extracting(asset -> asset.getFileId()).containsExactly(1001L, 1002L);
        assertThat(service.getAssets(7L)).hasSize(2);
    }

    @Test
    void taskPageIsScopedToApplicationSubjectKindAndStatus() {
        loginAs("alice");
        AiMediaTaskDO row = ownedTask(7L, AiMediaTaskDO.STATUS_QUEUED).setMediaKind(AiMediaTaskDO.KIND_IMAGE);
        when(taskMapper.selectPage(
                        any(PageParam.class), eq(APPLICATION_ID), eq("USER"), eq("alice"), eq("IMAGE"), eq("QUEUED")))
                .thenReturn(new PageResult<>(List.of(row), 1L));
        when(assetMapper.selectByTask(7L)).thenReturn(List.of());

        PageResult<AiMediaTaskResultDTO> page =
                service.getTaskPage(new PageParam().setPageNo(1).setPageSize(5), "IMAGE", "QUEUED");

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).extracting(AiMediaTaskResultDTO::getId).containsExactly(7L);
    }

    @Test
    void cancelOnQueuedWritesCancelledAndReturnsCurrentFacts() {
        loginAs("alice");
        when(taskMapper.selectById(6L))
                .thenReturn(ownedTask(6L, AiMediaTaskDO.STATUS_QUEUED), ownedTask(6L, AiMediaTaskDO.STATUS_CANCELLED));
        when(taskMapper.cancel(6L, APPLICATION_ID, "USER", "alice")).thenReturn(1);
        when(assetMapper.selectByTask(6L)).thenReturn(List.of());

        assertThat(service.cancel(6L).getStatus()).isEqualTo(AiMediaTaskDO.STATUS_CANCELLED);
        verify(taskMapper).cancel(6L, APPLICATION_ID, "USER", "alice");
    }

    @Test
    void cancelOnRunningIsRejectedWithStateConflict() {
        loginAs("alice");
        when(taskMapper.selectById(5L)).thenReturn(ownedTask(5L, AiMediaTaskDO.STATUS_RUNNING));
        when(taskMapper.cancel(5L, APPLICATION_ID, "USER", "alice")).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(5L)).isInstanceOfSatisfying(ServiceException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
            assertThat(exception.getMessage()).contains("当前状态不允许该操作");
        });
    }

    @Test
    void cancelOnTerminalTaskAnswersCurrentFactsInsteadOfPretendingCancellation() {
        loginAs("alice");
        when(taskMapper.selectById(8L))
                .thenReturn(ownedTask(8L, AiMediaTaskDO.STATUS_RUNNING), ownedTask(8L, AiMediaTaskDO.STATUS_SUCCEEDED));
        when(taskMapper.cancel(8L, APPLICATION_ID, "USER", "alice")).thenReturn(0);
        when(assetMapper.selectByTask(8L)).thenReturn(List.of());

        assertThat(service.cancel(8L).getStatus()).as("已终态按当前事实回答").isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
    }

    @Test
    void claimClampsLimitAndLeaseAndSkipsRowsTakenByAnotherWorker() {
        LocalDateTime before = LocalDateTime.now();
        AiMediaTaskDO taken = new AiMediaTaskDO().setId(1L).setStatus(AiMediaTaskDO.STATUS_QUEUED);
        AiMediaTaskDO won = new AiMediaTaskDO().setId(2L).setStatus(AiMediaTaskDO.STATUS_QUEUED);
        when(taskMapper.selectClaimable(any(LocalDateTime.class), eq(50))).thenReturn(List.of(taken, won));
        when(taskMapper.claim(eq(1L), eq(WORKER), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(0);
        when(taskMapper.claim(eq(2L), eq(WORKER), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(taskMapper.selectById(2L))
                .thenReturn(new AiMediaTaskDO()
                        .setId(2L)
                        .setLeaseOwner(WORKER)
                        .setClaimedEpoch(1)
                        .setAttemptCount(1));

        List<AiMediaTaskLeaseDTO> leases = service.claim(WORKER, 999, 999_999);

        assertThat(leases).hasSize(1);
        assertThat(leases.get(0).getTaskId()).as("被抢先的行必须放弃").isEqualTo(2L);
        assertThat(leases.get(0).getOwner()).isEqualTo(WORKER);
        assertThat(leases.get(0).getEpoch()).as("栅栏带领取纪元").isEqualTo(1);
        assertThat(leases.get(0).getAttempt()).isEqualTo(1);
        ArgumentCaptor<LocalDateTime> expiry = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskMapper).claim(eq(2L), eq(WORKER), expiry.capture(), any(LocalDateTime.class));
        assertThat(expiry.getValue())
                .as("租约上限被夹到 3600 秒")
                .isAfter(before.plusSeconds(3590))
                .isBefore(LocalDateTime.now().plusSeconds(3601));
    }

    @Test
    void claimUsesTheFloorOfOneSecondLeaseAndRejectsBlankWorker() {
        when(taskMapper.selectClaimable(any(LocalDateTime.class), eq(1))).thenReturn(List.of());
        assertThat(service.claim(WORKER, 0, 0)).isEmpty();
        assertThatThrownBy(() -> service.claim("  ", 5, 60)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void heartbeatRequiresLiveLeaseAndClampsSeconds() {
        LocalDateTime before = LocalDateTime.now();
        when(taskMapper.heartbeat(eq(3L), eq(WORKER), eq(1), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);

        assertThat(service.heartbeat(
                        new AiMediaTaskLeaseDTO().setTaskId(3L).setOwner(WORKER).setEpoch(1), 999_999))
                .isTrue();
        ArgumentCaptor<LocalDateTime> expiry = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskMapper).heartbeat(eq(3L), eq(WORKER), eq(1), expiry.capture(), any(LocalDateTime.class));
        assertThat(expiry.getValue())
                .isAfter(before.plusSeconds(3590))
                .isBefore(LocalDateTime.now().plusSeconds(3601));

        assertThat(service.heartbeat(null, 60)).isFalse();
    }

    @Test
    void finishRejectsNonTerminalStatusAndPassesUsageFactsThrough() {
        AiMediaTaskLeaseDTO lease =
                new AiMediaTaskLeaseDTO().setTaskId(3L).setOwner(WORKER).setEpoch(1);
        when(taskMapper.finish(3L, WORKER, 1, AiMediaTaskDO.STATUS_SUCCEEDED, null, 2, "TOKEN", 128L, "REPORTED"))
                .thenReturn(1);

        assertThat(service.finish(lease, AiMediaStepOutcome.succeeded(2, "TOKEN", 128L, "REPORTED")))
                .isTrue();
        assertThatThrownBy(
                        () -> service.finish(lease, new AiMediaStepOutcome().setStatus(AiMediaTaskDO.STATUS_RUNNING)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> service.finish(lease, new AiMediaStepOutcome().setStatus(AiMediaTaskDO.STATUS_CANCELLED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.finish(lease, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.finish(null, AiMediaStepOutcome.failed("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finishWithUnknownUsageWritesUnknownSourceAndZeroesNegativeResultCount() {
        AiMediaTaskLeaseDTO lease =
                new AiMediaTaskLeaseDTO().setTaskId(4L).setOwner(WORKER).setEpoch(2);
        when(taskMapper.finish(
                        4L, WORKER, 2, AiMediaTaskDO.STATUS_FAILED, "execution-failed", 0, null, null, "UNKNOWN"))
                .thenReturn(1);

        assertThat(service.finish(lease, AiMediaStepOutcome.failed("execution-failed")))
                .isTrue();
    }

    @Test
    void finishReportsTheFenceMissAsFalseInsteadOfOverwritingTerminalState() {
        AiMediaTaskLeaseDTO lease =
                new AiMediaTaskLeaseDTO().setTaskId(4L).setOwner(WORKER).setEpoch(1);
        when(taskMapper.finish(4L, WORKER, 1, AiMediaTaskDO.STATUS_SUCCEEDED, null, 1, null, null, "UNKNOWN"))
                .thenReturn(0);

        assertThat(service.finish(lease, AiMediaStepOutcome.succeeded(1, null, null, null)))
                .as("晚到的结果不能覆盖终态")
                .isFalse();
    }

    @Test
    void recoverExpiredLeasesClampsLimitAndRetryDelay() {
        when(taskMapper.recoverExpired(any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                .thenReturn(2);

        assertThat(service.recoverExpiredLeases(30, 500)).isEqualTo(2);
        verify(taskMapper).recoverExpired(any(LocalDateTime.class), any(LocalDateTime.class), eq(50));

        assertThat(service.recoverExpiredLeases(-5, 0)).isEqualTo(2);
        verify(taskMapper).recoverExpired(any(LocalDateTime.class), any(LocalDateTime.class), eq(1));
    }

    @Test
    void taskForExecutionIsLookedUpWithoutSubjectScope() {
        when(taskMapper.selectById(7L)).thenReturn(ownedTask(7L, AiMediaTaskDO.STATUS_RUNNING));

        assertThat(service.getTaskForExecution(7L).getId()).isEqualTo(7L);
        assertThat(service.getTaskForExecution(null)).isNull();
        verify(subjectResolver, never()).resolveCurrent();
    }

    private void loginAs(String externalUserId) {
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiFileSubject(APPLICATION_ID, AiSubjectType.USER, externalUserId, 9L)));
    }

    private static AiMediaTaskDO ownedTask(Long taskId, String status) {
        return new AiMediaTaskDO()
                .setId(taskId)
                .setApplicationId(APPLICATION_ID)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setStatus(status);
    }

    private static AiMediaTaskSubmitDTO generateRequest(String requestKey, String prompt) {
        return new AiMediaTaskSubmitDTO()
                .setRequestKey(requestKey)
                .setMediaKind(AiMediaTaskDO.KIND_IMAGE)
                .setOperation(AiMediaTaskDO.OPERATION_GENERATE)
                .setCapability("IMAGE_GENERATION")
                .setEndpointId(ENDPOINT_ID)
                .setInputText(prompt)
                .setTargetSize("1024x1024")
                .setOutputCount(1)
                .setOutputFormat("png");
    }
}
