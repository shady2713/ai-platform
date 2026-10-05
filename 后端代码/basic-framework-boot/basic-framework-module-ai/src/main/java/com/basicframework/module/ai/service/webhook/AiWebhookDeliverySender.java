package com.basicframework.module.ai.service.webhook;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookTargetDO;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Webhook 投递器（X10）：把一条投递行变成一次**受控出站**请求，并把结果分类成唯一结论。
 *
 * <p>边界：
 * <ul>
 *   <li><b>只走受控出站</b>：注入 {@link ExternalHttpClient}（F09 边界），绝不直接使用
 *       RestTemplate/HttpClient；边界不允许的目标在发送前被拒（零请求）；</li>
 *   <li><b>不跟随重定向</b>：3xx 原样返回并按确定失败收尾——改址必须人工重新登记目标；</li>
 *   <li><b>发送前复检目标</b>：停用/删除的目标不再投递（在途投递按事实收尾，不谎报成功）；</li>
 *   <li><b>密钥不外泄</b>：密钥只用于本地计算签名，不进请求 URL、不进日志、不进响应；
 *       请求头只有服务端构造的字段，不转发任何入站头的宿主凭据；</li>
 *   <li><b>异常也收敛</b>：任何未归类异常按可重试处理（有界重试，不假装成功）。</li>
 * </ul>
 */
@Slf4j
@Component
public class AiWebhookDeliverySender {

    /** User-Agent（接收端可据此识别平台版本家族；不含实例信息）。 */
    static final String USER_AGENT = "basic-framework-ai-webhook/1.0";

    /** 请求内容类型（正文是平台构造的规范化 JSON）。 */
    static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private final ObjectProvider<ExternalHttpClient> externalHttpClient;

    private final AiWebhookTargetService targetService;

    private final Duration requestTimeout;

    /** 构造器注入：出站客户端可能不存在（未启用 AI 能力），按可重试失败处理而不是崩掉作业。 */
    public AiWebhookDeliverySender(
            ObjectProvider<ExternalHttpClient> externalHttpClient,
            AiWebhookTargetService targetService,
            @Value("${basic-framework.ai.webhook.request-timeout-seconds:20}") int requestTimeoutSeconds) {
        this.externalHttpClient = externalHttpClient;
        this.targetService = targetService;
        this.requestTimeout = Duration.ofSeconds(Math.max(1, requestTimeoutSeconds));
    }

    /**
     * 投递一次（不写库：尝试留痕与状态迁移由 {@link AiWebhookDeliveryService#finish} 在同一事务里落库）。
     *
     * @param delivery 投递行（正文与编号入队时已冻结）
     */
    public AiWebhookDeliveryOutcome deliver(AiWebhookDeliveryDO delivery) {
        long startedNanos = System.nanoTime();
        AiWebhookTargetDO target;
        try {
            target = targetService.get(delivery.getTargetId());
        } catch (ServiceException notFound) {
            // 目标已被删除：停用即停发（不发请求，等价的确定失败）
            return AiWebhookDeliveryOutcome.permanent(
                    AiWebhookFailureCodes.TARGET_DISABLED, null, null, elapsed(startedNanos));
        }
        if (!AiWebhookTargetDO.STATUS_ENABLED.equals(target.getStatus())) {
            return AiWebhookDeliveryOutcome.permanent(
                    AiWebhookFailureCodes.TARGET_DISABLED, null, null, elapsed(startedNanos));
        }
        String secret = targetService.decryptSecret(target.getId());
        if (secret == null) {
            // 未配置密钥或密文不可解密：不发"签不出来"的请求
            return AiWebhookDeliveryOutcome.permanent(
                    AiWebhookFailureCodes.SIGNING_KEY_UNAVAILABLE, null, null, elapsed(startedNanos));
        }
        ExternalHttpClient client = externalHttpClient.getIfAvailable();
        if (client == null) {
            // 出站边界未装配：按可重试处理（有界收敛，不假装送达），并留下可观测原因
            log.warn("Webhook 投递 {} 未装配受控出站客户端，本次按可重试失败处理", delivery.getDeliveryNo());
            return AiWebhookDeliveryOutcome.retryable(
                    AiWebhookFailureCodes.INTERNAL_ERROR, null, null, elapsed(startedNanos));
        }
        long timestamp = Instant.now().getEpochSecond();
        byte[] body = delivery.getPayloadJson().getBytes(StandardCharsets.UTF_8);
        try {
            ExternalHttpResponse response = client.execute(new ExternalHttpRequest(
                    "POST", target.getTargetUrl(), headers(delivery, timestamp, secret, body), body, requestTimeout));
            return classify(response, timestamp, elapsed(startedNanos));
        } catch (ExternalHttpException exception) {
            return mapBoundaryFailure(exception, timestamp, elapsed(startedNanos));
        } catch (RuntimeException exception) {
            // 未归类异常：按可重试处理，只落稳定原因码，不落异常正文
            log.warn("Webhook 投递 {} 出现未归类异常（原因已脱敏）", delivery.getDeliveryNo());
            return AiWebhookDeliveryOutcome.retryable(
                    AiWebhookFailureCodes.INTERNAL_ERROR, null, timestamp, elapsed(startedNanos));
        }
    }

    /** 请求头：全部由服务端构造（不转发入站头），签名覆盖时间戳 + 投递编号 + 正文摘要。 */
    private static Map<String, String> headers(
            AiWebhookDeliveryDO delivery, long timestamp, String secret, byte[] body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", CONTENT_TYPE);
        headers.put("User-Agent", USER_AGENT);
        headers.put(AiWebhookSignature.HEADER_DELIVERY_NO, delivery.getDeliveryNo());
        headers.put(AiWebhookSignature.HEADER_TIMESTAMP, String.valueOf(timestamp));
        headers.put(AiWebhookSignature.HEADER_EVENT, delivery.getEventType());
        headers.put(AiWebhookSignature.HEADER_ATTEMPT, String.valueOf(delivery.getAttemptCount()));
        headers.put(AiWebhookSignature.HEADER_RESOURCE, delivery.getResourceType() + ":" + delivery.getResourceKey());
        headers.put(
                AiWebhookSignature.HEADER_SIGNATURE,
                AiWebhookSignature.sign(secret, String.valueOf(timestamp), delivery.getDeliveryNo(), body));
        return headers;
    }

    /** 接收端响应分类：只有 2xx 算送达；3xx/4xx 是确定失败；5xx 与 429 才重试。 */
    private static AiWebhookDeliveryOutcome classify(ExternalHttpResponse response, long timestamp, long durationMs) {
        int status = response.status();
        if (status >= 200 && status < 300) {
            return AiWebhookDeliveryOutcome.delivered(status, timestamp, durationMs);
        }
        if (status == 429) {
            return AiWebhookDeliveryOutcome.retryable(
                    AiWebhookFailureCodes.HTTP_RATE_LIMITED, status, timestamp, durationMs);
        }
        if (status >= 300 && status < 400) {
            return AiWebhookDeliveryOutcome.permanent(
                    AiWebhookFailureCodes.REDIRECT_NOT_FOLLOWED, status, timestamp, durationMs);
        }
        if (status >= 500) {
            return AiWebhookDeliveryOutcome.retryable(
                    AiWebhookFailureCodes.HTTP_SERVER_ERROR, status, timestamp, durationMs);
        }
        // 其余（4xx 与 1xx）：接收端明确不受理，重试同一请求没有意义
        return AiWebhookDeliveryOutcome.permanent(
                AiWebhookFailureCodes.HTTP_CLIENT_ERROR, status, timestamp, durationMs);
    }

    /** 受控出站边界的拒绝/失败 → 稳定结论：目标未授权与请求不合法是确定失败，超时与连接失败可重试。 */
    private static AiWebhookDeliveryOutcome mapBoundaryFailure(
            ExternalHttpException exception, Long timestamp, long durationMs) {
        return switch (exception.getReason()) {
            case TIMEOUT ->
                AiWebhookDeliveryOutcome.retryable(AiWebhookFailureCodes.TIMEOUT, null, timestamp, durationMs);
            // F12：取消此前以 CONNECT_FAILED 到达这里（F12 之前守卫把它收敛进兜底分支）。
            // 可重试性保持不变——"被取消是否应重投"是 X10 的决策，不在本卡改归因。
            case CANCELLED, CONNECT_FAILED ->
                AiWebhookDeliveryOutcome.retryable(AiWebhookFailureCodes.CONNECT_FAILED, null, timestamp, durationMs);
            case TARGET_NOT_ALLOWED ->
                AiWebhookDeliveryOutcome.permanent(
                        AiWebhookFailureCodes.TARGET_NOT_ALLOWED, null, timestamp, durationMs);
            case PRIVATE_TARGET_DENIED ->
                AiWebhookDeliveryOutcome.permanent(
                        AiWebhookFailureCodes.PRIVATE_TARGET_DENIED, null, timestamp, durationMs);
            case INVALID_REQUEST ->
                AiWebhookDeliveryOutcome.permanent(AiWebhookFailureCodes.REQUEST_INVALID, null, timestamp, durationMs);
            case RESPONSE_TOO_LARGE ->
                AiWebhookDeliveryOutcome.permanent(
                        AiWebhookFailureCodes.RESPONSE_TOO_LARGE, null, timestamp, durationMs);
        };
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
