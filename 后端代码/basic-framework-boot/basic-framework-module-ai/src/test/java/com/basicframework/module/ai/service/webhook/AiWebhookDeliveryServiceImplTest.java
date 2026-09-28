package com.basicframework.module.ai.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryAttemptDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookTargetDO;
import com.basicframework.module.ai.dal.mysql.webhook.AiWebhookDeliveryAttemptMapper;
import com.basicframework.module.ai.dal.mysql.webhook.AiWebhookDeliveryMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.webhook.dto.AiWebhookDeliveryLeaseDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Webhook 投递服务（X10）：入队冻结正文与编号、领取跳过被抢先行、落结论的三条分支、
 * 栅栏失效不谎报成功、人工重投只对死信且目标必须启用、尝试留痕与保留期清理。
 */
@ExtendWith(MockitoExtension.class)
class AiWebhookDeliveryServiceImplTest {

    private static final Long DELIVERY_ID = 55L;

    private static final Long TARGET_ID = 91L;

    private static final String OWNER = "webhook-delivery-abc12345";

    /** 扫描锚点：用例里统一"数据里的现在"，让窗口断言与时钟无关。 */
    private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 9, 27, 9, 30);

    @Mock
    private AiWebhookDeliveryMapper deliveryMapper;

    @Mock
    private AiWebhookDeliveryAttemptMapper attemptMapper;

    @Mock
    private AiWebhookTargetService targetService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private AiWebhookDeliveryServiceImpl service;

    @BeforeEach
    void setUp() {
        // 批事务在本用例里只是"照常提交"的边界：写路径的有界性/恢复语义由断言与集成用例钉住
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // 最近窗口取 1 分钟：用例用"水位是否落后于最近窗口"决定是否走历史补漏路径
        service = new AiWebhookDeliveryServiceImpl(
                deliveryMapper, attemptMapper, targetService, transactionManager, 30, 600, 1);
    }

    @Test
    void enqueueFreezesDeliveryNoPayloadDigestAndAttemptBudget() {
        // 水位落后于最近窗口：历史补漏先走（本用例让它给出候选），最近窗口再走（无候选）
        when(targetService.listEnabled())
                .thenReturn(List.of(enabledTarget("[\"RUN.SUCCEEDED\"]", LocalDateTime.of(2026, 9, 27, 8, 0))));
        when(deliveryMapper.selectLatestRunUpdateTime(7L)).thenReturn(ANCHOR);
        when(deliveryMapper.selectRecentTerminalRuns(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(List.of());
        when(deliveryMapper.selectPendingCandidates(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(List.of(candidate()));

        int created = service.enqueueTerminalRuns(50);

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<AiWebhookDeliveryDO> captor = ArgumentCaptor.forClass(AiWebhookDeliveryDO.class);
        verify(deliveryMapper).insert(captor.capture());
        AiWebhookDeliveryDO delivery = captor.getValue();
        assertThat(delivery.getDeliveryNo()).startsWith("whd_").hasSize(36);
        assertThat(delivery.getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_PENDING);
        assertThat(delivery.getAttemptCount()).isZero();
        assertThat(delivery.getMaxAttempts()).isEqualTo(5);
        assertThat(delivery.getClaimedEpoch()).isZero();
        assertThat(delivery.getNextAttemptTime()).isNotNull();
        assertThat(delivery.getPayloadJson())
                .isEqualTo("{\"schemaVersion\":\"1.0\",\"eventType\":\"RUN.SUCCEEDED\",\"resourceType\":\"RUN\","
                        + "\"resourceKey\":\"run_abc\",\"status\":\"SUCCEEDED\","
                        + "\"occurredAt\":\"2026-09-27T09:00:00\"}");
        assertThat(delivery.getPayloadDigest())
                .isEqualTo(AiWebhookSignature.sha256Hex(
                        delivery.getPayloadJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void enqueueTreatsConcurrentWinnerAsAlreadyRecorded() {
        // 水位已追上：只走最近窗口（重复入队由唯一键拒绝，按"已存在的是事实"计数为 0）
        when(targetService.listEnabled()).thenReturn(List.of(enabledTarget("[\"RUN.SUCCEEDED\"]", ANCHOR)));
        when(deliveryMapper.selectLatestRunUpdateTime(7L)).thenReturn(ANCHOR);
        when(deliveryMapper.selectRecentTerminalRuns(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(List.of(candidate()));
        when(deliveryMapper.insert(any(AiWebhookDeliveryDO.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThat(service.enqueueTerminalRuns(0)).isZero();
    }

    /** 扫描谓词只用「订阅状态集合 + 水位窗口」：不再把事件白名单交给 SQL 逐行做 JSON 解析。 */
    @Test
    void enqueueRequestsOnlySubscribedRunStatusesInsideTheWatermarkWindow() {
        LocalDateTime watermark = ANCHOR.minusHours(3);
        when(targetService.listEnabled())
                .thenReturn(List.of(enabledTarget("[\"RUN.SUCCEEDED\",\"RUN.CANCELLED\"]", watermark)));
        when(deliveryMapper.selectLatestRunUpdateTime(7L)).thenReturn(ANCHOR);
        when(deliveryMapper.selectRecentTerminalRuns(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(List.of());
        when(deliveryMapper.selectPendingCandidates(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(List.of());

        service.enqueueTerminalRuns(50);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> statuses = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<LocalDateTime> floor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> ceiling = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(deliveryMapper)
                .selectPendingCandidates(
                        eq(TARGET_ID),
                        eq(7L),
                        statuses.capture(),
                        floor.capture(),
                        ceiling.capture(),
                        eq(5),
                        limit.capture());
        assertThat(statuses.getValue()).containsExactly(AiRunDO.STATUS_SUCCEEDED, AiRunDO.STATUS_CANCELLED);
        assertThat(floor.getValue()).isEqualTo(watermark);
        // 历史一段的右端是最近窗口的左端（锚点 - 最近窗口长度）：两段不重叠，最近窗口单独每轮重扫
        assertThat(ceiling.getValue()).isEqualTo(ANCHOR.minusMinutes(1));
        assertThat(limit.getValue()).isLessThanOrEqualTo(50).isPositive();
    }

    /** 水位已经追上最近窗口时不再扫历史：稳态每轮只有"最近窗口"这一条有界扫描。 */
    @Test
    void enqueueSkipsTheBacklogOnceTheWatermarkHasCaughtUp() {
        when(targetService.listEnabled())
                .thenReturn(List.of(enabledTarget("[\"RUN.SUCCEEDED\"]", ANCHOR.minusSeconds(1))));
        when(deliveryMapper.selectLatestRunUpdateTime(7L)).thenReturn(ANCHOR);
        when(deliveryMapper.selectRecentTerminalRuns(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(List.of());

        service.enqueueTerminalRuns(50);

        verify(deliveryMapper, never())
                .selectPendingCandidates(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt());
        // 最近窗口每轮都重扫：它同时兜住"提交晚于扫描"的行，因此水位追上后也不能停
        verify(deliveryMapper)
                .selectRecentTerminalRuns(
                        eq(TARGET_ID),
                        eq(7L),
                        eq(List.of(AiRunDO.STATUS_SUCCEEDED)),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        eq(5),
                        eq(50));
        verify(targetService, never()).advanceEnqueueWatermark(anyLong(), any(LocalDateTime.class));
    }

    /** 整段覆盖（候选不足 limit 条）→ 水位推进到这段的右端：下一轮只需接着往前推进。 */
    @Test
    void enqueueAdvancesTheWatermarkToTheCoveredEdgeWhenTheWindowIsExhausted() {
        LocalDateTime watermark = ANCHOR.minusHours(1);
        when(targetService.listEnabled()).thenReturn(List.of(enabledTarget("[\"RUN.SUCCEEDED\"]", watermark)));
        when(deliveryMapper.selectLatestRunUpdateTime(7L)).thenReturn(ANCHOR);
        when(deliveryMapper.selectRecentTerminalRuns(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(List.of());
        when(deliveryMapper.selectPendingCandidates(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(List.of(candidate()));

        assertThat(service.enqueueTerminalRuns(50)).isEqualTo(1);

        ArgumentCaptor<LocalDateTime> backlogCeiling = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(deliveryMapper)
                .selectPendingCandidates(
                        eq(TARGET_ID), eq(7L), any(), eq(watermark), backlogCeiling.capture(), anyInt(), anyInt());
        ArgumentCaptor<LocalDateTime> advanced = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(targetService).advanceEnqueueWatermark(eq(TARGET_ID), advanced.capture());
        // 整段覆盖 → 水位推进到这段的右端（下一轮从那里继续）
        assertThat(advanced.getValue()).isEqualTo(backlogCeiling.getValue());
    }

    /** 单条入队失败只推进到最后一条成功的候选：失败那条下一轮仍会被读到（不静默丢失）。 */
    @Test
    void enqueueNeverAdvancesTheWatermarkPastAFailedCandidate() {
        LocalDateTime watermark = ANCHOR.minusHours(1);
        AiWebhookDeliveryDO first = candidate().setResourceId(1L).setOccurredTime(ANCHOR.minusMinutes(30));
        AiWebhookDeliveryDO second = candidate().setResourceId(2L).setOccurredTime(ANCHOR.minusMinutes(20));
        when(targetService.listEnabled()).thenReturn(List.of(enabledTarget("[\"RUN.SUCCEEDED\"]", watermark)));
        when(deliveryMapper.selectLatestRunUpdateTime(7L)).thenReturn(ANCHOR);
        when(deliveryMapper.selectRecentTerminalRuns(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(List.of());
        when(deliveryMapper.selectPendingCandidates(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt()))
                .thenReturn(List.of(first, second));
        when(deliveryMapper.insert(any(AiWebhookDeliveryDO.class)))
                .thenReturn(1)
                .thenThrow(new IllegalStateException("db down"));

        assertThat(service.enqueueTerminalRuns(50)).isEqualTo(1);

        ArgumentCaptor<LocalDateTime> advanced = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(targetService).advanceEnqueueWatermark(eq(TARGET_ID), advanced.capture());
        assertThat(advanced.getValue()).isEqualTo(first.getOccurredTime());
    }

    /** 没有订阅任何终态事件：既不入队也不推进水位（重新订阅后仍能从原水位补齐）。 */
    @Test
    void enqueueSkipsTargetsWithoutSubscribedTerminalEvents() {
        when(targetService.listEnabled())
                .thenReturn(List.of(
                        enabledTarget("[\"RUN.RUNNING\"]", LocalDateTime.now().minusDays(1))));

        assertThat(service.enqueueTerminalRuns(50)).isZero();

        verify(deliveryMapper, never())
                .selectPendingCandidates(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt());
        verify(targetService, never()).advanceEnqueueWatermark(anyLong(), any(LocalDateTime.class));
    }

    /** 应用还没有任何运行：没有可扫的数据，既不查窗口也不推进水位。 */
    @Test
    void enqueueDoesNothingWhenTheApplicationHasNoRunsYet() {
        when(targetService.listEnabled())
                .thenReturn(List.of(
                        enabledTarget("[\"RUN.SUCCEEDED\"]", LocalDateTime.now().minusDays(1))));
        when(deliveryMapper.selectLatestRunUpdateTime(7L)).thenReturn(null);

        assertThat(service.enqueueTerminalRuns(50)).isZero();

        verify(deliveryMapper, never())
                .selectRecentTerminalRuns(
                        anyLong(),
                        anyLong(),
                        any(),
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        anyInt(),
                        anyInt());
        verify(targetService, never()).advanceEnqueueWatermark(anyLong(), any(LocalDateTime.class));
    }

    private static AiWebhookTargetDO enabledTarget(String eventTypes, LocalDateTime watermark) {
        return new AiWebhookTargetDO()
                .setId(TARGET_ID)
                .setApplicationId(7L)
                .setStatus(AiWebhookTargetDO.STATUS_ENABLED)
                .setEventTypes(eventTypes)
                .setMaxAttempts(5)
                .setEnqueueWatermark(watermark);
    }

    @Test
    void claimSkipsRowsLostToAnotherWorker() {
        AiWebhookDeliveryDO first = new AiWebhookDeliveryDO().setId(1L);
        AiWebhookDeliveryDO second = new AiWebhookDeliveryDO().setId(2L);
        when(deliveryMapper.selectClaimable(any(LocalDateTime.class), anyInt())).thenReturn(List.of(first, second));
        when(deliveryMapper.claim(eq(1L), eq(OWNER), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(0);
        when(deliveryMapper.claim(eq(2L), eq(OWNER), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(deliveryMapper.selectById(2L))
                .thenReturn(new AiWebhookDeliveryDO()
                        .setId(2L)
                        .setLeaseOwner(OWNER)
                        .setClaimedEpoch(1)
                        .setAttemptCount(1)
                        .setStatus(AiWebhookDeliveryDO.STATUS_RUNNING));

        List<AiWebhookDeliveryLeaseDTO> leases = service.claim(OWNER, 5, 60);

        assertThat(leases).hasSize(1);
        assertThat(leases.get(0).getDeliveryId()).isEqualTo(2L);
        assertThat(leases.get(0).getOwner()).isEqualTo(OWNER);
        assertThat(leases.get(0).getEpoch()).isEqualTo(1);
        assertThat(leases.get(0).getAttempt()).isEqualTo(1);
    }

    @Test
    void finishDeliveredRecordsAttemptAndMarksSucceeded() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(runningDelivery(1, 3));
        when(deliveryMapper.markDelivered(eq(DELIVERY_ID), eq(OWNER), eq(1), any(LocalDateTime.class)))
                .thenReturn(1);

        boolean applied = service.finish(lease(1), AiWebhookDeliveryOutcome.delivered(204, 1_790_000_000L, 42L));

        assertThat(applied).isTrue();
        ArgumentCaptor<AiWebhookDeliveryAttemptDO> captor = ArgumentCaptor.forClass(AiWebhookDeliveryAttemptDO.class);
        verify(attemptMapper).insert(captor.capture());
        AiWebhookDeliveryAttemptDO attempt = captor.getValue();
        assertThat(attempt.getAttemptNo()).isEqualTo(1);
        assertThat(attempt.getOutcome()).isEqualTo(AiWebhookDeliveryAttemptDO.OUTCOME_DELIVERED);
        assertThat(attempt.getErrorCode()).isNull();
        assertThat(attempt.getHttpStatus()).isEqualTo(204);
        assertThat(attempt.getSignatureTimestamp()).isEqualTo(1_790_000_000L);
        assertThat(attempt.getDurationMs()).isEqualTo(42L);
        assertThat(attempt.getStartedTime()).isBeforeOrEqualTo(attempt.getFinishedTime());
    }

    @Test
    void finishPermanentFailureStoresTheSameCodeAsFailureAndLastError() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(runningDelivery(1, 3));
        when(deliveryMapper.markFailed(
                        eq(DELIVERY_ID), eq(OWNER), eq(1), eq("redirect-not-followed"), eq("redirect-not-followed")))
                .thenReturn(1);

        boolean applied = service.finish(
                lease(1), AiWebhookDeliveryOutcome.permanent(AiWebhookFailureCodes.REDIRECT_NOT_FOLLOWED, 302, 7L, 1L));

        assertThat(applied).isTrue();
        verify(deliveryMapper, never()).markRetry(anyLong(), any(), anyInt(), any(), any());
    }

    @Test
    void finishRetryableFailureBacksOffExponentiallyWithACap() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(runningDelivery(2, 5));
        when(deliveryMapper.markRetry(eq(DELIVERY_ID), eq(OWNER), eq(1), any(LocalDateTime.class), eq("timeout")))
                .thenReturn(1);

        boolean applied = service.finish(
                lease(2), AiWebhookDeliveryOutcome.retryable(AiWebhookFailureCodes.TIMEOUT, null, 7L, 1L));

        assertThat(applied).isTrue();
        ArgumentCaptor<LocalDateTime> nextAttempt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(deliveryMapper).markRetry(eq(DELIVERY_ID), eq(OWNER), eq(1), nextAttempt.capture(), eq("timeout"));
        long delaySeconds =
                ChronoUnit.SECONDS.between(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS), nextAttempt.getValue());
        assertThat(delaySeconds).isBetween(59L, 61L);

        // 第 6 次失败时退避被 600 秒封顶
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(runningDelivery(6, 10));
        when(deliveryMapper.markRetry(eq(DELIVERY_ID), eq(OWNER), eq(1), any(LocalDateTime.class), any()))
                .thenReturn(1);
        service.finish(lease(6), AiWebhookDeliveryOutcome.retryable(AiWebhookFailureCodes.TIMEOUT, null, null, 1L));
        ArgumentCaptor<LocalDateTime> capped = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(deliveryMapper, org.mockito.Mockito.times(2))
                .markRetry(eq(DELIVERY_ID), eq(OWNER), eq(1), capped.capture(), any());
        long cappedSeconds = ChronoUnit.SECONDS.between(
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                capped.getAllValues().get(1));
        assertThat(cappedSeconds).isBetween(599L, 601L);
    }

    @Test
    void finishRetryableFailureAtTheBudgetLimitBecomesAnExhaustedDeadLetter() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(runningDelivery(3, 3));
        when(deliveryMapper.markFailed(
                        eq(DELIVERY_ID),
                        eq(OWNER),
                        eq(1),
                        eq(AiWebhookFailureCodes.DELIVERY_EXHAUSTED),
                        eq("http-server-error")))
                .thenReturn(1);

        boolean applied = service.finish(
                lease(3), AiWebhookDeliveryOutcome.retryable(AiWebhookFailureCodes.HTTP_SERVER_ERROR, 503, 7L, 1L));

        assertThat(applied).isTrue();
        verify(deliveryMapper, never()).markRetry(anyLong(), any(), anyInt(), any(), any());
    }

    @Test
    void finishReportsLostFencesInsteadOfPretendingSuccess() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(runningDelivery(1, 3));
        when(deliveryMapper.markDelivered(eq(DELIVERY_ID), eq(OWNER), eq(1), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThat(service.finish(lease(1), AiWebhookDeliveryOutcome.delivered(200, 1L, 1L)))
                .isFalse();
        // 尝试留痕仍然保留（这次尝试真实发生过）
        verify(attemptMapper).insert(any(AiWebhookDeliveryAttemptDO.class));
    }

    @Test
    void finishWithoutVisibleDeliveryRowIsNotRecorded() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(null);

        assertThat(service.finish(lease(1), AiWebhookDeliveryOutcome.delivered(200, 1L, 1L)))
                .isFalse();
        verify(attemptMapper, never()).insert(any(AiWebhookDeliveryAttemptDO.class));
    }

    @Test
    void redeliverRejectsNonDeadLetters() {
        when(deliveryMapper.selectById(DELIVERY_ID))
                .thenReturn(new AiWebhookDeliveryDO()
                        .setId(DELIVERY_ID)
                        .setTargetId(TARGET_ID)
                        .setStatus(AiWebhookDeliveryDO.STATUS_PENDING));

        assertThatThrownBy(() -> service.redeliver(DELIVERY_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue(
                        "code", AiErrorCodeConstants.AI_WEBHOOK_DELIVERY_NOT_REDELIVERABLE.getCode());
        verify(deliveryMapper, never()).redeliver(anyLong(), anyInt(), any());
    }

    @Test
    void redeliverRejectsDisabledTargets() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(deadLetter());
        when(targetService.get(TARGET_ID))
                .thenReturn(new AiWebhookTargetDO().setId(TARGET_ID).setStatus(AiWebhookTargetDO.STATUS_DISABLED));

        assertThatThrownBy(() -> service.redeliver(DELIVERY_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_WEBHOOK_TARGET_DISABLED.getCode());
        verify(deliveryMapper, never()).redeliver(anyLong(), anyInt(), any());
    }

    @Test
    void redeliverRejectsDeletedTargets() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(deadLetter());
        org.mockito.Mockito.doThrow(new ServiceException(AiErrorCodeConstants.AI_WEBHOOK_TARGET_NOT_FOUND))
                .when(targetService)
                .get(TARGET_ID);

        assertThatThrownBy(() -> service.redeliver(DELIVERY_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_WEBHOOK_TARGET_DISABLED.getCode());
    }

    @Test
    void redeliverPutsTheDeadLetterBackIntoTheQueue() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(deadLetter());
        when(targetService.get(TARGET_ID))
                .thenReturn(new AiWebhookTargetDO().setId(TARGET_ID).setStatus(AiWebhookTargetDO.STATUS_ENABLED));
        when(deliveryMapper.redeliver(eq(DELIVERY_ID), anyInt(), any(LocalDateTime.class)))
                .thenReturn(1);

        service.redeliver(DELIVERY_ID);

        // 人工重投追加一份与目标配置一致的重试预算（尝试序号保持单调递增）
        verify(deliveryMapper).redeliver(eq(DELIVERY_ID), eq(3), any(LocalDateTime.class));
    }

    @Test
    void redeliverRejectsConcurrentChange() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(deadLetter());
        when(targetService.get(TARGET_ID))
                .thenReturn(new AiWebhookTargetDO().setId(TARGET_ID).setStatus(AiWebhookTargetDO.STATUS_ENABLED));
        when(deliveryMapper.redeliver(eq(DELIVERY_ID), anyInt(), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.redeliver(DELIVERY_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
    }

    @Test
    void readsAndRetentionFollowTheRecordedRows() {
        when(deliveryMapper.selectById(DELIVERY_ID)).thenReturn(runningDelivery(1, 3));
        assertThat(service.get(DELIVERY_ID).getId()).isEqualTo(DELIVERY_ID);
        assertThat(service.getForExecution(DELIVERY_ID).getId()).isEqualTo(DELIVERY_ID);
        assertThat(service.getForExecution(null)).isNull();

        when(attemptMapper.selectByDelivery(DELIVERY_ID)).thenReturn(List.of(new AiWebhookDeliveryAttemptDO()));
        assertThat(service.getAttempts(DELIVERY_ID)).hasSize(1);

        when(deliveryMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.get(404L))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_WEBHOOK_DELIVERY_NOT_FOUND.getCode());
        assertThatThrownBy(() -> service.getAttempts(404L))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_WEBHOOK_DELIVERY_NOT_FOUND.getCode());

        when(attemptMapper.deleteBefore(any(LocalDateTime.class), anyInt())).thenReturn(3);
        assertThat(service.pruneAttempts(Duration.ofDays(30), 5_000)).isEqualTo(3);
        ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(attemptMapper).deleteBefore(before.capture(), limit.capture());
        assertThat(before.getValue()).isBefore(LocalDateTime.now().minusDays(29));
        assertThat(limit.getValue()).isEqualTo(200);

        // 未提供保留期时按默认 30 天，且负数保留期不放大清理范围
        assertThat(service.pruneAttempts(null, 1)).isEqualTo(3);
        assertThat(service.pruneAttempts(Duration.ofDays(-1), 1)).isEqualTo(3);
    }

    @Test
    void recoverExpiredLeasesRewritesThemWithTheExhaustedCodeAvailable() {
        when(deliveryMapper.recoverExpired(any(LocalDateTime.class), any(LocalDateTime.class), any(), anyInt()))
                .thenReturn(2);

        assertThat(service.recoverExpiredLeases(10, 100)).isEqualTo(2);
        verify(deliveryMapper)
                .recoverExpired(
                        any(LocalDateTime.class),
                        any(LocalDateTime.class),
                        eq(AiWebhookFailureCodes.DELIVERY_EXHAUSTED),
                        eq(50));
    }

    private static AiWebhookDeliveryDO candidate() {
        return new AiWebhookDeliveryDO()
                .setTargetId(TARGET_ID)
                .setApplicationId(7L)
                .setEventType("RUN.SUCCEEDED")
                .setResourceType("RUN")
                .setResourceId(12L)
                .setResourceKey("run_abc")
                .setOccurredTime(LocalDateTime.of(2026, 9, 27, 9, 0, 0))
                .setMaxAttempts(5);
    }

    private static AiWebhookDeliveryDO runningDelivery(int attemptCount, int maxAttempts) {
        return new AiWebhookDeliveryDO()
                .setId(DELIVERY_ID)
                .setTargetId(TARGET_ID)
                .setDeliveryNo("whd_1")
                .setStatus(AiWebhookDeliveryDO.STATUS_RUNNING)
                .setAttemptCount(attemptCount)
                .setMaxAttempts(maxAttempts);
    }

    /** 死信：达到重试预算的失败投递（人工重投的唯一合法输入）。 */
    private static AiWebhookDeliveryDO deadLetter() {
        return new AiWebhookDeliveryDO()
                .setId(DELIVERY_ID)
                .setTargetId(TARGET_ID)
                .setDeliveryNo("whd_1")
                .setStatus(AiWebhookDeliveryDO.STATUS_FAILED)
                .setAttemptCount(3)
                .setMaxAttempts(3)
                .setFailureCode(AiWebhookFailureCodes.DELIVERY_EXHAUSTED);
    }

    private static AiWebhookDeliveryLeaseDTO lease(int attempt) {
        return new AiWebhookDeliveryLeaseDTO()
                .setDeliveryId(DELIVERY_ID)
                .setOwner(OWNER)
                .setEpoch(1)
                .setAttempt(attempt);
    }
}
