package com.basicframework.module.ai.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunIdempotencyDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunIdempotencyMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.runtime.AiRunRequestDigest;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.conversation.dto.AiConversationRunContextDTO;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptDTO;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptResultDTO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceRunSnapshotDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/** O02 运行受理：幂等复用、同键异摘要 409、版本固定、并发冲突回读与响应不含秘密。 */
class AiRunServiceImplTest {

    private static final Long APP_ID = 5L;

    private static final String EXTERNAL_USER = "u-1001";

    private static final Long SERVICE_ID = 9L;

    private static final Long CONVERSATION_ID = 31L;

    private static final Long RELEASE_ID = 21L;

    private static final String IDEMPOTENCY_KEY = "idem-0123456789abcdef";

    private final AiRunMapper runMapper = mock(AiRunMapper.class);

    private final AiRunIdempotencyMapper idempotencyMapper = mock(AiRunIdempotencyMapper.class);

    private final AiRunAcceptanceWriter acceptanceWriter = mock(AiRunAcceptanceWriter.class);

    private final AiConversationSubjectResolver subjectResolver = mock(AiConversationSubjectResolver.class);

    private final AiConversationService conversationService = mock(AiConversationService.class);

    private final AiServiceService serviceService = mock(AiServiceService.class);

    private final AiServiceReleaseService releaseService = mock(AiServiceReleaseService.class);

    private final AiRunServiceImpl service = new AiRunServiceImpl(
            runMapper,
            idempotencyMapper,
            acceptanceWriter,
            subjectResolver,
            conversationService,
            serviceService,
            releaseService);

    @BeforeEach
    void setUp() {
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiConversationSubject(APP_ID, AiSubjectType.USER, EXTERNAL_USER)));
        when(serviceService.getService(SERVICE_ID))
                .thenReturn(new AiServiceDO().setId(SERVICE_ID).setAppId(APP_ID));
        when(releaseService.resolveForNewRun(SERVICE_ID)).thenReturn(snapshot());
        when(releaseService.listReleases(SERVICE_ID)).thenReturn(List.of(release()));
    }

    private static AiServiceReleaseDO release() {
        return new AiServiceReleaseDO()
                .setId(RELEASE_ID)
                .setServiceId(SERVICE_ID)
                .setReleaseVersion(2)
                .setContentHash("a".repeat(64))
                .setStatus(AiServiceReleaseDO.STATUS_ACTIVE);
    }

    private static AiServiceRunSnapshotDTO snapshot() {
        return new AiServiceRunSnapshotDTO()
                .setRelease(release())
                .setBindings(List.of())
                .setPin(com.basicframework.module.ai.domain.runtime.AiRunSnapshot.of(release(), List.of()))
                .setPinned(false);
    }

    private static AiRunAcceptDTO acceptDTO() {
        return new AiRunAcceptDTO()
                .setServiceId(SERVICE_ID)
                .setIdempotencyKey(IDEMPOTENCY_KEY)
                .setMessage("帮我查订单")
                .setAttachmentKeys(List.of("file-a"))
                .setBusinessContext("{\"page\":\"order\"}")
                .setDataLevel("L2_INTERNAL");
    }

    private static AiRunDO run() {
        return new AiRunDO()
                .setId(41L)
                .setRunKey("run_0123456789abcdef01234567")
                .setApplicationId(APP_ID)
                .setSubjectType("USER")
                .setExternalUserId(EXTERNAL_USER)
                .setServiceId(SERVICE_ID)
                .setReleaseId(RELEASE_ID)
                .setModelEndpointId(1L)
                .setEndpointConfigRevision(3)
                .setContentHash("a".repeat(64))
                .setStatus(AiRunDO.STATUS_ACCEPTED)
                .setVersion(0);
    }

    private static String digestOf(AiRunAcceptDTO acceptDTO) {
        return AiRunRequestDigest.compute(
                acceptDTO.getServiceId(),
                acceptDTO.getConversationId(),
                acceptDTO.getMessage(),
                acceptDTO.getAttachmentKeys(),
                acceptDTO.getBusinessContext());
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void acceptCreatesRunIdempotencyAndFirstTaskInOneTransaction() {
        when(idempotencyMapper.selectByKey(APP_ID, "USER", EXTERNAL_USER, IDEMPOTENCY_KEY))
                .thenReturn(null);
        when(acceptanceWriter.create(any(), any(), any(), any(), any())).thenReturn(run());

        AiRunAcceptResultDTO result = service.accept(acceptDTO());

        ArgumentCaptor<AiRunAcceptDTO> dtoCaptor = ArgumentCaptor.forClass(AiRunAcceptDTO.class);
        ArgumentCaptor<AiServiceRunSnapshotDTO> snapshotCaptor = ArgumentCaptor.forClass(AiServiceRunSnapshotDTO.class);
        ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
        verify(acceptanceWriter)
                .create(any(), dtoCaptor.capture(), digestCaptor.capture(), snapshotCaptor.capture(), any());
        assertThat(digestCaptor.getValue()).isEqualTo(digestOf(acceptDTO()));
        assertThat(snapshotCaptor.getValue().getRelease().getId()).isEqualTo(RELEASE_ID);

        assertThat(result.getRunId()).isEqualTo(41L);
        assertThat(result.getRunKey()).startsWith("run_");
        assertThat(result.getStatus()).isEqualTo(AiRunDO.STATUS_ACCEPTED);
        assertThat(result.getReleaseVersion()).isEqualTo(2);
        assertThat(result.isReused()).as("首次受理不是复用").isFalse();
        assertThat(result.toString())
                .as("受理结果不含凭据、token 或请求正文")
                .doesNotContain("帮我查订单")
                .doesNotContain("sk-");
    }

    @Test
    void sameKeySameDigestReusesTheOriginalRunWithoutCallingTheWriter() {
        when(idempotencyMapper.selectByKey(APP_ID, "USER", EXTERNAL_USER, IDEMPOTENCY_KEY))
                .thenReturn(new AiRunIdempotencyDO()
                        .setId(51L)
                        .setIdempotencyKey(IDEMPOTENCY_KEY)
                        .setRequestDigest(digestOf(acceptDTO()))
                        .setRunId(41L));
        when(runMapper.selectById(41L)).thenReturn(run());

        AiRunAcceptResultDTO result = service.accept(acceptDTO());

        assertThat(result.isReused()).isTrue();
        assertThat(result.getRunId()).isEqualTo(41L);
        verify(acceptanceWriter, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void sameKeyDifferentDigestIsRejectedWithoutCreatingAnything() {
        when(idempotencyMapper.selectByKey(APP_ID, "USER", EXTERNAL_USER, IDEMPOTENCY_KEY))
                .thenReturn(new AiRunIdempotencyDO()
                        .setId(51L)
                        .setIdempotencyKey(IDEMPOTENCY_KEY)
                        .setRequestDigest("b".repeat(64))
                        .setRunId(41L));

        assertThatThrownBy(() -> service.accept(acceptDTO().setMessage("换个问题")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT));
        verify(acceptanceWriter, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void concurrentConflictRereadsTheWinnerAndComparesDigest() {
        // 首次读取没有记录；写入时撞上唯一键；回读拿到赢家记录
        when(idempotencyMapper.selectByKey(APP_ID, "USER", EXTERNAL_USER, IDEMPOTENCY_KEY))
                .thenReturn(null)
                .thenReturn(new AiRunIdempotencyDO()
                        .setId(51L)
                        .setIdempotencyKey(IDEMPOTENCY_KEY)
                        .setRequestDigest(digestOf(acceptDTO()))
                        .setRunId(41L));
        when(acceptanceWriter.create(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_ai_run_idempotency_key"));
        when(runMapper.selectById(41L)).thenReturn(run());

        AiRunAcceptResultDTO result = service.accept(acceptDTO());

        assertThat(result.isReused()).as("并发冲突后复用赢家的运行").isTrue();
        assertThat(result.getRunId()).isEqualTo(41L);

        // 赢家摘要不同：409（不能用旧结果冒充新请求）
        when(idempotencyMapper.selectByKey(APP_ID, "USER", EXTERNAL_USER, IDEMPOTENCY_KEY))
                .thenReturn(null)
                .thenReturn(new AiRunIdempotencyDO()
                        .setIdempotencyKey(IDEMPOTENCY_KEY)
                        .setRequestDigest("c".repeat(64))
                        .setRunId(41L));
        assertThatThrownBy(() -> service.accept(acceptDTO()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT));

        // 回读不到记录（例如运行键碰撞）：按冲突结束，不返回假成功
        when(idempotencyMapper.selectByKey(APP_ID, "USER", EXTERNAL_USER, IDEMPOTENCY_KEY))
                .thenReturn(null);
        assertThatThrownBy(() -> service.accept(acceptDTO()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT));
    }

    @Test
    void conversationWithPinnedReleaseKeepsThatVersion() {
        AiRunAcceptDTO withConversation = acceptDTO().setConversationId(CONVERSATION_ID);
        when(idempotencyMapper.selectByKey(APP_ID, "USER", EXTERNAL_USER, IDEMPOTENCY_KEY))
                .thenReturn(null);
        when(conversationService.loadRunContext(CONVERSATION_ID, 1))
                .thenReturn(new AiConversationRunContextDTO()
                        .setConversation(new AiConversationDO()
                                .setId(CONVERSATION_ID)
                                .setServiceId(SERVICE_ID)
                                .setReleaseId(RELEASE_ID)
                                .setStatus(AiConversationDO.STATUS_ACTIVE)
                                .setVersion(2))
                        .setPin(com.basicframework.module.ai.domain.runtime.AiRunSnapshot.of(release(), List.of())));
        when(releaseService.resolvePinnedRun(any())).thenReturn(snapshot());
        when(acceptanceWriter.create(any(), any(), any(), any(), any())).thenReturn(run());

        AiRunAcceptResultDTO result = service.accept(withConversation);

        verify(releaseService).resolvePinnedRun(any());
        verify(releaseService, never()).resolveForNewRun(anyLong());
        assertThat(result.getRunId()).isEqualTo(41L);
    }

    @Test
    void conversationWithoutPinnedReleaseBindsTheResolvedVersion() {
        AiRunAcceptDTO withConversation = acceptDTO().setConversationId(CONVERSATION_ID);
        when(idempotencyMapper.selectByKey(APP_ID, "USER", EXTERNAL_USER, IDEMPOTENCY_KEY))
                .thenReturn(null);
        when(conversationService.loadRunContext(CONVERSATION_ID, 1))
                .thenReturn(new AiConversationRunContextDTO()
                        .setConversation(new AiConversationDO()
                                .setId(CONVERSATION_ID)
                                .setServiceId(SERVICE_ID)
                                .setStatus(AiConversationDO.STATUS_ACTIVE)
                                .setVersion(2)));
        when(acceptanceWriter.create(any(), any(), any(), any(), any())).thenReturn(run());

        service.accept(withConversation);

        verify(conversationService).bindRelease(CONVERSATION_ID, RELEASE_ID, 2);
    }

    @Test
    void rejectsInvalidInputsAndForeignServices() {
        assertThatThrownBy(() -> service.accept(acceptDTO().setIdempotencyKey("short")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.accept(acceptDTO().setMessage(" ")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.accept(acceptDTO().setDataLevel("L9")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.accept(acceptDTO().setAttachmentKeys(List.of("x".repeat(129)))))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.accept(acceptDTO().setBusinessContext("not-json")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));

        when(serviceService.getService(SERVICE_ID))
                .thenReturn(new AiServiceDO().setId(SERVICE_ID).setAppId(77L));
        assertThatThrownBy(() -> service.accept(acceptDTO()))
                .as("不能受理其它应用的服务")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));

        when(subjectResolver.resolveCurrent()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.accept(acceptDTO()))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
        verify(acceptanceWriter, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void runReadsAreScopedToTheCurrentSubject() {
        when(runMapper.selectOwned(41L, APP_ID, "USER", EXTERNAL_USER)).thenReturn(run());
        assertThat(service.getRun(41L).getRunKey()).startsWith("run_");

        when(runMapper.selectOwned(42L, APP_ID, "USER", EXTERNAL_USER)).thenReturn(null);
        assertThatThrownBy(() -> service.getRun(42L))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        assertThatThrownBy(() -> service.getRun(null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
    }

    @Test
    void acceptanceWriterCreatesQueuedFirstTaskWithPayloadDigestOnly() {
        AiRunAcceptanceWriter writer = new AiRunAcceptanceWriter(
                runMapper, idempotencyMapper, mock(com.basicframework.module.ai.dal.mysql.run.AiRunTaskMapper.class));
        assertThat(AiRunAcceptanceWriter.newRunKey()).startsWith("run_").hasSize(28);

        com.basicframework.module.ai.dal.mysql.run.AiRunTaskMapper taskMapper =
                mock(com.basicframework.module.ai.dal.mysql.run.AiRunTaskMapper.class);
        AiRunAcceptanceWriter transactionalWriter = new AiRunAcceptanceWriter(runMapper, idempotencyMapper, taskMapper);
        org.mockito.Mockito.doAnswer(invocation -> {
                    ((AiRunDO) invocation.getArgument(0)).setId(41L);
                    return 1;
                })
                .when(runMapper)
                .insert(any(AiRunDO.class));

        transactionalWriter.create(
                new AiConversationSubject(APP_ID, AiSubjectType.USER, EXTERNAL_USER),
                acceptDTO(),
                digestOf(acceptDTO()),
                snapshot(),
                "run_0123456789abcdef01234567");

        ArgumentCaptor<AiRunTaskDO> taskCaptor = ArgumentCaptor.forClass(AiRunTaskDO.class);
        verify(taskMapper).insert(taskCaptor.capture());
        AiRunTaskDO task = taskCaptor.getValue();
        assertThat(task.getStatus()).isEqualTo(AiRunTaskDO.STATUS_QUEUED);
        assertThat(task.getTaskKind()).isEqualTo(AiRunTaskDO.KIND_RUN_STEP);
        assertThat(task.getAttemptCount()).isZero();
        assertThat(task.getPayloadDigest())
                .as("队列只保存载荷摘要，不保存正文")
                .isEqualTo(digestOf(acceptDTO()))
                .isNotEqualTo("帮我查订单");
        assertThat(writer).isNotNull();
    }

    @Test
    void pageIsScopedToTheCurrentSubject() {
        when(acceptanceWriter.page(any(), any()))
                .thenReturn(new com.basicframework.framework.common.pojo.PageResult<>(List.of(run()), 1L));

        assertThat(service.getPage(new PageParam()).getList()).hasSize(1);
        verify(acceptanceWriter).page(any(), eq(new AiConversationSubject(APP_ID, AiSubjectType.USER, EXTERNAL_USER)));
    }
}
