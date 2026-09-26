package com.basicframework.module.ai.service.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.service.usage.dto.AiUsageRecordDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Q02 计量落账本：来源如实标注、无归属不写假行、计量失败不断业务链。 */
class AiUsageLedgerRecorderTest {

    private final AiUsageLedgerService usageLedgerService = mock(AiUsageLedgerService.class);

    private final AiUsageLedgerRecorder recorder = new AiUsageLedgerRecorder(usageLedgerService);

    private static AiModelInvocationRecord record(boolean estimated, Integer prompt, Integer completion) {
        return new AiModelInvocationRecord()
                .setInvocationId("inv_1")
                .setEndpointId(3L)
                .setConfigRevision(2)
                .setStatus("SUCCEEDED")
                .setLatencyMs(120L)
                .setPromptTokens(prompt)
                .setCompletionTokens(completion)
                .setEstimated(estimated)
                .setApplicationId(1L)
                .setServiceId(4L)
                .setRunId(10L)
                .setModelRef("qwen-plus")
                .setSubjectRef("app:1/subject:abc");
    }

    /** 取**最近一次**落账本调用的入参（同一用例里可能已发生多次记录）。 */
    private AiUsageRecordDTO capture() {
        ArgumentCaptor<AiUsageRecordDTO> captor = ArgumentCaptor.forClass(AiUsageRecordDTO.class);
        verify(usageLedgerService, atLeastOnce()).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void mapsReportedEstimatedAndUnknownSourcesTruthfully() {
        when(usageLedgerService.record(any(AiUsageRecordDTO.class))).thenReturn(true);

        recorder.record(record(false, 10, 20));
        assertThat(capture().getUsageSource()).isEqualTo("REPORTED");
        assertThat(capture().getInputTokens()).isEqualTo(10L);

        recorder.record(record(true, 8, null));
        assertThat(capture().getUsageSource()).isEqualTo("ESTIMATED");

        // 上游没给用量：来源 UNKNOWN，token 保持为空（绝不写 0 冒充实测）
        recorder.record(record(false, null, null));
        assertThat(capture().getUsageSource()).isEqualTo("UNKNOWN");
        assertThat(capture().getInputTokens()).isNull();
        assertThat(capture().getOutputTokens()).isNull();
    }

    @Test
    void skipsWithoutApplicationContextInsteadOfInventingOne() {
        recorder.record(record(false, 1, 1).setApplicationId(null));

        verify(usageLedgerService, never()).record(any(AiUsageRecordDTO.class));
    }

    @Test
    void neverBreaksCallChainOnMeteringFailure() {
        when(usageLedgerService.record(any(AiUsageRecordDTO.class))).thenThrow(new IllegalStateException("db down"));

        recorder.record(record(false, 1, 1));

        verify(usageLedgerService).record(any(AiUsageRecordDTO.class));
        // 空记录直接忽略
        recorder.record(null);
    }

    @Test
    void endpointReferenceIsStoredAsReferenceNotAddress() {
        when(usageLedgerService.record(any(AiUsageRecordDTO.class))).thenReturn(true);
        recorder.record(record(false, 1, 1));

        assertThat(capture().getEndpointRef()).isEqualTo("endpoint:3");
        assertThat(capture().getModelRevision()).isEqualTo(2);
        assertThat(capture().getStatus()).isEqualTo("SUCCEEDED");
    }
}
