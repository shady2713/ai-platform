package com.basicframework.module.ai.service.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.usage.AiUsageLedgerDO;
import com.basicframework.module.ai.dal.mysql.usage.AiUsageLedgerMapper;
import com.basicframework.module.ai.service.usage.dto.AiUsageRecordDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** Q02 用量账本：调用级去重、来源如实标注、聚合口径。 */
class AiUsageLedgerServiceTest {

    private final AiUsageLedgerMapper mapper = mock(AiUsageLedgerMapper.class);

    private final AiUsageLedgerServiceImpl service = new AiUsageLedgerServiceImpl(mapper);

    private static AiUsageRecordDTO record(String source, Long input, Long output) {
        return new AiUsageRecordDTO()
                .setInvocationId("inv_1")
                .setApplicationId(1L)
                .setRunId(10L)
                .setModelRef("qwen-plus")
                .setStatus("SUCCEEDED")
                .setUsageSource(source)
                .setInputTokens(input)
                .setOutputTokens(output);
    }

    @Test
    void recordsOncePerInvocationAndSwallowsDuplicate() {
        when(mapper.insert(any(AiUsageLedgerDO.class))).thenReturn(1).thenThrow(new DuplicateKeyException("uk"));

        assertThat(service.record(record("REPORTED", 10L, 20L))).isTrue();
        // 同一调用重复上报：不重复累计，也不报错
        assertThat(service.record(record("REPORTED", 10L, 20L))).isFalse();
    }

    @Test
    void rejectsUnknownSourceWithTokensAndMissingIdentity() {
        assertThatThrownBy(() -> service.record(record("UNKNOWN", 10L, null)))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(1_003_001_000));
        // UNKNOWN 且不携带 token 是合法记录（"用量未知"必须能记下来）
        when(mapper.insert(any(AiUsageLedgerDO.class))).thenReturn(1);
        assertThat(service.record(record("UNKNOWN", null, null))).isTrue();

        assertThatThrownBy(() -> service.record(record("REPORTED", 1L, 1L).setUsageSource("GUESSED")))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.record(record("REPORTED", 1L, 1L).setInvocationId(null)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() ->
                        service.record(record("REPORTED", 1L, 1L).setRunId(null).setTaskId(null)))
                .isInstanceOf(ServiceException.class);
        // 端点只接受引用：地址形态直接拒绝
        assertThatThrownBy(
                        () -> service.record(record("REPORTED", 1L, 1L).setEndpointRef("https://model.example.com/v1")))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.record(null)).isInstanceOf(ServiceException.class);
    }

    @Test
    void summaryExposesUnknownInvocationCount() {
        when(mapper.aggregateBySource(anyLong(), any(), any()))
                .thenReturn(List.of(
                        Map.of("usageSource", "REPORTED", "invocationCount", 3L, "inputTokens", 30L),
                        Map.of("usageSource", "ESTIMATED", "invocationCount", 1L, "inputTokens", 8L),
                        Map.of("usageSource", "UNKNOWN", "invocationCount", 2L, "inputTokens", 0L)));

        Map<String, Object> summary =
                service.summaryBySource(1L, LocalDateTime.now().minusDays(1), LocalDateTime.now());

        // 未知条数是显式事实：调用方据此提示"用量为估算/未知"，而不是当精确值
        assertThat(summary.get("unknownInvocations")).isEqualTo(2L);
        assertThat(summary.get("sources")).isInstanceOf(List.class);
    }

    @Test
    void aggregationRequiresAValidWindow() {
        assertThatThrownBy(() -> service.summaryBySource(1L, null, LocalDateTime.now()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.summaryByService(
                        1L, LocalDateTime.now(), LocalDateTime.now().minusDays(1)))
                .isInstanceOf(ServiceException.class);
        verify(mapper, never()).aggregateByService(anyLong(), any(), any());
        assertThat(service.listByRun(null)).isEmpty();
    }
}
