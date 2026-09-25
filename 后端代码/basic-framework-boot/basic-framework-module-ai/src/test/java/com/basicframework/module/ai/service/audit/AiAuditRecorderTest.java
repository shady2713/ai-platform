package com.basicframework.module.ai.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Q01 审计事件与日志脱敏：事件只有结构化事实、秘密与正文进不去、上游异常只留摘要。
 */
class AiAuditRecorderTest {

    private final AiAuditRecorder recorder = new AiAuditRecorder();

    private static AiAuditEvent event(String eventType, String runRef, List<String> resourceRefs) {
        return new AiAuditEvent(
                eventType,
                runRef,
                "app:1/subject:abc123",
                resourceRefs,
                "READ",
                AiAuditEvent.OUTCOME_OK,
                12L,
                LocalDateTime.of(2026, 9, 25, 10, 0));
    }

    @Test
    void recordsStructuredFactsWithoutFreeText() {
        String line =
                recorder.record(event(AiAuditEventType.RESOURCE_ACCESS.name(), "run_ab12", List.of("REPORT:rpt_ab12")));

        assertThat(line)
                .contains("event=RESOURCE_ACCESS")
                .contains("run=run_ab12")
                .contains("subject=app:1/subject:abc123")
                .contains("resources=REPORT:rpt_ab12")
                .contains("action=READ")
                .contains("outcome=AI_OK")
                .contains("durationMs=12");
        // 事件里没有"问题正文/提示词/片段"这类字段
        assertThat(line).doesNotContain("question", "prompt", "snippet", "message=");
    }

    @Test
    void unknownEventTypeAndTooManyResourcesAreRejected() {
        assertThatThrownBy(() -> recorder.record(event("SOMETHING_ELSE", "run_1", List.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(1_003_001_000));
        assertThatThrownBy(() -> recorder.record(event(
                        AiAuditEventType.RUN_ACCEPTED.name(),
                        "run_1",
                        IntStream.range(0, 21)
                                .mapToObj(index -> "REPORT:rpt_" + index)
                                .toList())))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> recorder.record(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void sensitiveFieldValuesAreReplacedNotTruncated() {
        String line = recorder.record(event(
                AiAuditEventType.FILE_ACCESS.name(),
                "run_aitkt_sessiontoken1234567890",
                List.of("FILE:aitkt_once_abcdefg")));

        assertThat(line).contains(AiAuditLogSanitizer.REDACTED);
        assertThat(line).doesNotContain("aitkt_");
        // 长字段整体替换（不留下可定位账号的前缀）
        String longRef = "x".repeat(200);
        assertThat(AiAuditLogSanitizer.sanitizeField(longRef, 128)).isEqualTo(AiAuditLogSanitizer.REDACTED);
        assertThat(AiAuditLogSanitizer.sanitizeField("run_ab12", 128)).isEqualTo("run_ab12");
        assertThat(AiAuditLogSanitizer.sanitizeField(null, 128)).isEqualTo("-");
    }

    @Test
    void sanitizerCoversTokensSecretsConnectionStringsSqlAndKeys() {
        for (String sensitive : new String[] {
            "token=aitkt_once_abcdefg",
            "appSecret=aiapp_realsecret",
            "password: hunter2hunter2",
            "Authorization: Bearer abcdefghijklmnop",
            "jdbc:mysql://db:3306/app?user=root&password=x",
            "SELECT id FROM orders WHERE tenant_id = 42",
            "-----BEGIN RSA PRIVATE KEY-----",
            "{\"apiKey\":\"sk-live-abcdefghijklmnop\"}"
        }) {
            assertThat(AiAuditLogSanitizer.containsSensitiveContent(sensitive))
                    .as("应识别为敏感：%s", sensitive)
                    .isTrue();
            assertThat(AiAuditLogSanitizer.sanitize(sensitive)).isEqualTo(AiAuditLogSanitizer.REDACTED);
        }

        for (String safe : new String[] {
            "run_ab12",
            "REPORT:rpt_ab12",
            "outcome=AI_CONTEXT_SCHEMA_INVALID",
            "durationMs=128",
            "stage=MODEL_CALL",
            null,
            ""
        }) {
            assertThat(AiAuditLogSanitizer.containsSensitiveContent(safe)).isFalse();
            assertThat(AiAuditLogSanitizer.sanitizeField(safe, 128))
                    .isIn(safe == null || safe.isEmpty() ? "-" : safe, "-");
        }
    }

    @Test
    void upstreamFailureKeepsOnlyOutcomeAndStage() {
        String line = recorder.upstreamFailure(
                "run_ab12",
                "MODEL_CALL",
                "AI_MODEL_CALL_FAILED",
                new IllegalStateException("GET https://model.example.com/v1?token=aitkt_once_abcdefg 失败"));

        assertThat(line)
                .contains("outcome=AI_MODEL_CALL_FAILED")
                .contains("STAGE:MODEL_CALL")
                .doesNotContain("aitkt_")
                .doesNotContain("https://model.example.com");
    }

    @Test
    void convenienceEntryPointsUseKnownTypesAndStableOutcomes() {
        assertThat(recorder.runAccepted("run_1", "app:1/subject:a", List.of("SERVICE:svc_1"), 5L))
                .contains("event=RUN_ACCEPTED")
                .contains("outcome=AI_OK");
        assertThat(recorder.runFinished("run_1", "app:1/subject:a", "AI_MODEL_CALL_FAILED", 88L))
                .contains("event=RUN_FINISHED")
                .contains("outcome=AI_MODEL_CALL_FAILED");
    }

    @Test
    void eventTypeCatalogIsClosed() {
        assertThat(AiAuditEventType.isKnown("RUN_ACCEPTED")).isTrue();
        assertThat(AiAuditEventType.isKnown("OPS_DIAGNOSTIC")).isTrue();
        assertThat(AiAuditEventType.isKnown("run_accepted")).isFalse();
        assertThat(AiAuditEventType.isKnown(null)).isFalse();
        assertThat(AiAuditEventType.values()).hasSize(6);
    }
}
