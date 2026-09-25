package com.basicframework.module.ai.service.audit;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.util.exception.SafeExceptionLogUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计记录器（Q01）：把 {@link AiAuditEvent} 写进**专用日志通道**。
 *
 * <p>为什么用独立 logger 名（`AI_AUDIT`）而不是复用通用访问日志：
 * 审计事件要能被日志平台单独采集、单独设置保留期，并与业务访问日志分开授权查看。
 * 事件内容由 {@link AiAuditEvent} 保证"只有结构化事实"，本类再对每个字段做一次
 * {@link AiAuditLogSanitizer#sanitizeField}（纵深防御：调用方拼错字段也不会带出秘密）。
 *
 * <p>异常只走 {@link SafeExceptionLogUtils}（框架既有的安全异常日志），不带入异常正文——
 * 上游异常正文里可能有连接串、SQL 与响应体。
 */
@Component
public class AiAuditRecorder {

    /** 专用审计通道（日志平台按 logger 名采集）。 */
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AI_AUDIT");

    /** 诊断通道：只放框架安全格式化后的异常摘要（与审计事件分开授权查看）。 */
    private static final Logger DIAGNOSTIC_LOG = LoggerFactory.getLogger(AiAuditRecorder.class);

    private static final int MAX_REF_LENGTH = 128;

    private static final int MAX_RESOURCE_REFS = 20;

    /** 记录一条审计事件（返回结构化行，便于单测断言）。 */
    public String record(AiAuditEvent event) {
        Objects.requireNonNull(event, "audit event 不能为空");
        if (!AiAuditEventType.isKnown(event.eventType())) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (event.resourceRefs().size() > MAX_RESOURCE_REFS) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiAuditEvent safe = new AiAuditEvent(
                event.eventType(),
                AiAuditLogSanitizer.sanitizeField(event.runRef(), MAX_REF_LENGTH),
                AiAuditLogSanitizer.sanitizeField(event.subjectRef(), MAX_REF_LENGTH),
                event.resourceRefs().stream()
                        .map(ref -> AiAuditLogSanitizer.sanitizeField(ref, MAX_REF_LENGTH))
                        .toList(),
                AiAuditLogSanitizer.sanitizeField(event.action(), 32),
                AiAuditLogSanitizer.sanitizeField(event.outcomeCode(), 64),
                event.durationMs(),
                event.occurredAt() == null ? LocalDateTime.now() : event.occurredAt());
        String line = safe.toLogLine();
        AUDIT_LOG.info(line);
        return line;
    }

    /** 便捷入口：运行受理。 */
    public String runAccepted(String runRef, String subjectRef, List<String> resourceRefs, long durationMs) {
        return record(new AiAuditEvent(
                AiAuditEventType.RUN_ACCEPTED.name(),
                runRef,
                subjectRef,
                resourceRefs,
                "EXECUTE",
                AiAuditEvent.OUTCOME_OK,
                durationMs,
                LocalDateTime.now()));
    }

    /** 便捷入口：运行终态（失败时只记稳定错误码）。 */
    public String runFinished(String runRef, String subjectRef, String outcomeCode, long durationMs) {
        return record(new AiAuditEvent(
                AiAuditEventType.RUN_FINISHED.name(),
                runRef,
                subjectRef,
                List.of(),
                "EXECUTE",
                outcomeCode,
                durationMs,
                LocalDateTime.now()));
    }

    /**
     * 上游失败的安全留痕：**只记错误码与阶段**，异常本体交给框架的安全异常日志工具。
     *
     * <p>这是"日志全链路检查"（AT-058）的关键点：上游异常正文里常有连接串、SQL 与响应体，
     * 直接打 `log.error("失败", e)` 会把它们带出去。
     */
    public String upstreamFailure(String runRef, String stage, String outcomeCode, Throwable cause) {
        String line = record(new AiAuditEvent(
                AiAuditEventType.RUN_FINISHED.name(),
                runRef,
                null,
                List.of("STAGE:" + AiAuditLogSanitizer.sanitizeField(stage, 32)),
                "EXECUTE",
                outcomeCode,
                AiAuditEvent.DURATION_UNKNOWN,
                LocalDateTime.now()));
        // 异常本体经框架的安全格式化（有界长度 + 有界 cause 链，不带入上游响应体），
        // 且只进诊断通道：审计事件保持"只有结构化事实"。
        DIAGNOSTIC_LOG.warn(
                "AI 上游失败 run={} stage={} outcome={} detail={}",
                AiAuditLogSanitizer.sanitizeField(runRef, MAX_REF_LENGTH),
                AiAuditLogSanitizer.sanitizeField(stage, 32),
                AiAuditLogSanitizer.sanitizeField(outcomeCode, 64),
                SafeExceptionLogUtils.format(cause));
        return line;
    }
}
