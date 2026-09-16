package com.basicframework.framework.common.util.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * {@link TracerUtils} 单元测试
 *
 */
class TracerUtilsTest {

    @Test
    void getTraceId_returnsTraceContextTraceId() {
        try (MockedStatic<TraceContext> traceContext = mockStatic(TraceContext.class)) {
            traceContext.when(TraceContext::traceId).thenReturn("trace-abc-123");

            assertThat(TracerUtils.getTraceId()).isEqualTo("trace-abc-123");
        }
    }

    @Test
    void getTraceId_returnsEmptyStringOutsideTrace() {
        try (MockedStatic<TraceContext> traceContext = mockStatic(TraceContext.class)) {
            traceContext.when(TraceContext::traceId).thenReturn("");

            assertThat(TracerUtils.getTraceId()).isEmpty();
        }
    }

    @Test
    void getTraceId_delegatesLiveValue() {
        try (MockedStatic<TraceContext> traceContext = mockStatic(TraceContext.class)) {
            traceContext.when(TraceContext::traceId).thenReturn("trace-1", "trace-2");

            assertThat(TracerUtils.getTraceId()).isEqualTo("trace-1");
            assertThat(TracerUtils.getTraceId()).isEqualTo("trace-2");
        }
    }
}
