package com.basicframework.module.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryDO;
import com.basicframework.module.ai.service.webhook.AiWebhookDeliveryOutcome;
import com.basicframework.module.ai.service.webhook.AiWebhookDeliverySender;
import com.basicframework.module.ai.service.webhook.AiWebhookDeliveryService;
import com.basicframework.module.ai.service.webhook.AiWebhookFailureCodes;
import com.basicframework.module.ai.service.webhook.dto.AiWebhookDeliveryLeaseDTO;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Webhook 投递 Job（X10）：编排顺序、单条失败不拖垮整批、栅栏失效不计数、摘要只含计数。
 *
 * <p>本 Job 只读写投递与尝试表：用例顺带断言它**从不**调用任何运行/任务写入
 * （它只依赖投递服务与投递器两个协作者，结构上就没有回写运行的路径）。
 */
@ExtendWith(MockitoExtension.class)
class AiWebhookDeliveryJobTest {

    private static final Long DELIVERY_ID = 55L;

    @Mock
    private AiWebhookDeliveryService deliveryService;

    @Mock
    private AiWebhookDeliverySender sender;

    private AiWebhookDeliveryJob job;

    @BeforeEach
    void setUp() {
        job = new AiWebhookDeliveryJob(
                deliveryService, sender, "webhook-test", 5, 60, 10, 50, 50, 200, Duration.ofDays(30));
    }

    @Test
    void executeReportsCountsAndPrunesAttempts() {
        when(deliveryService.recoverExpiredLeases(10, 50)).thenReturn(0);
        when(deliveryService.enqueueTerminalRuns(50)).thenReturn(2);
        when(deliveryService.claim(any(), eq(5), eq(60))).thenReturn(List.of(lease(1), lease(2), lease(3)));
        when(deliveryService.getForExecution(any())).thenReturn(runningDelivery());
        when(sender.deliver(any()))
                .thenReturn(AiWebhookDeliveryOutcome.delivered(200, 1L, 1L))
                .thenReturn(AiWebhookDeliveryOutcome.retryable(AiWebhookFailureCodes.TIMEOUT, null, 1L, 1L))
                .thenReturn(
                        AiWebhookDeliveryOutcome.permanent(AiWebhookFailureCodes.REDIRECT_NOT_FOLLOWED, 302, 1L, 1L));
        when(deliveryService.finish(any(), any())).thenReturn(true);
        when(deliveryService.pruneAttempts(any(), eq(200))).thenReturn(7);

        String summary = job.execute(null);

        assertThat(summary).isEqualTo("recovered=0,enqueued=2,claimed=3,delivered=1,retried=1,failed=1,pruned=7");
    }

    @Test
    void aFailingDeliveryDoesNotAbortTheBatch() {
        when(deliveryService.claim(any(), anyInt(), anyInt())).thenReturn(List.of(lease(1), lease(2)));
        when(deliveryService.getForExecution(any())).thenReturn(runningDelivery());
        when(sender.deliver(any()))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(AiWebhookDeliveryOutcome.delivered(200, 1L, 1L));
        when(deliveryService.finish(any(), any())).thenReturn(true);

        String summary = job.execute(null);

        assertThat(summary).contains("claimed=2,delivered=1,retried=1,failed=0");
        assertThat(summary).doesNotContain("boom");
    }

    @Test
    void deliveriesLostToTheFenceOrWithoutARowAreNotCounted() {
        when(deliveryService.claim(any(), anyInt(), anyInt())).thenReturn(List.of(lease(1), lease(2)));
        when(deliveryService.getForExecution(any()))
                .thenReturn(null)
                .thenReturn(new AiWebhookDeliveryDO().setStatus(AiWebhookDeliveryDO.STATUS_SUCCEEDED));
        when(deliveryService.pruneAttempts(any(), anyInt())).thenReturn(0);

        String summary = job.execute(null);

        assertThat(summary).isEqualTo("recovered=0,enqueued=0,claimed=2,delivered=0,retried=0,failed=0,pruned=0");
        verify(sender, never()).deliver(any());
    }

    @Test
    void lostFenceResultsAreDroppedInsteadOfCountedTwice() {
        when(deliveryService.claim(any(), anyInt(), anyInt())).thenReturn(List.of(lease(1)));
        when(deliveryService.getForExecution(any())).thenReturn(runningDelivery());
        when(sender.deliver(any())).thenReturn(AiWebhookDeliveryOutcome.delivered(200, 1L, 1L));
        when(deliveryService.finish(any(), any())).thenReturn(false);

        String summary = job.execute(null);

        assertThat(summary).isEqualTo("recovered=0,enqueued=0,claimed=1,delivered=0,retried=0,failed=0,pruned=0");
    }

    @Test
    void persistenceFailureOfTheConclusionLeavesTheDeliveryForLeaseRecovery() {
        when(deliveryService.claim(any(), anyInt(), anyInt())).thenReturn(List.of(lease(1)));
        when(deliveryService.getForExecution(any())).thenReturn(runningDelivery());
        when(sender.deliver(any())).thenReturn(AiWebhookDeliveryOutcome.delivered(200, 1L, 1L));
        when(deliveryService.finish(any(), any())).thenThrow(new IllegalStateException("db down"));

        String summary = job.execute(null);

        assertThat(summary).isEqualTo("recovered=0,enqueued=0,claimed=1,delivered=0,retried=0,failed=0,pruned=0");
    }

    private static AiWebhookDeliveryLeaseDTO lease(int attempt) {
        return new AiWebhookDeliveryLeaseDTO()
                .setDeliveryId(DELIVERY_ID)
                .setOwner("webhook-test-1")
                .setEpoch(1)
                .setAttempt(attempt);
    }

    private static AiWebhookDeliveryDO runningDelivery() {
        return new AiWebhookDeliveryDO()
                .setId(DELIVERY_ID)
                .setDeliveryNo("whd_aabb")
                .setStatus(AiWebhookDeliveryDO.STATUS_RUNNING)
                .setAttemptCount(1)
                .setMaxAttempts(3);
    }
}
