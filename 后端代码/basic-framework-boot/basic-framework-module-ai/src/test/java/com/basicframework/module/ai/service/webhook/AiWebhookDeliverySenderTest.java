package com.basicframework.module.ai.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookTargetDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Webhook 投递器（X10）：只走受控出站、发送前复检目标、只对可重试结果重试、签名与密钥不外泄。
 *
 * <p>断言落在"发出去的请求"与"回来的结论"上：请求头由服务端构造、签名可被接收端校验、
 * 密钥不出现在任何请求材料里、3xx 与 4xx 不会进入重试。
 */
@ExtendWith(MockitoExtension.class)
class AiWebhookDeliverySenderTest {

    private static final Long TARGET_ID = 91L;

    private static final String SECRET = "s3cret-signing-key-0123456789";

    private static final String PAYLOAD =
            "{\"schemaVersion\":\"1.0\",\"eventType\":\"RUN.SUCCEEDED\",\"resourceType\":\"RUN\","
                    + "\"resourceKey\":\"run_abc\",\"status\":\"SUCCEEDED\",\"occurredAt\":\"2026-09-27T09:00:00\"}";

    @Mock
    private ExternalHttpClient httpClient;

    @Mock
    private AiWebhookTargetService targetService;

    @Mock
    private ObjectProvider<ExternalHttpClient> clientProvider;

    private AiWebhookDeliverySender sender;

    @BeforeEach
    void setUp() {
        sender = new AiWebhookDeliverySender(clientProvider, targetService, 20);
    }

    @Test
    void deliveredResponseCarriesAVerifiableSignatureAndNoSecretLeak() {
        enableTarget();
        when(clientProvider.getIfAvailable()).thenReturn(httpClient);
        when(httpClient.execute(any(ExternalHttpRequest.class))).thenReturn(response(200));

        AiWebhookDeliveryOutcome outcome = sender.deliver(delivery());

        assertThat(outcome.isDelivered()).isTrue();
        assertThat(outcome.httpStatus()).isEqualTo(200);
        ArgumentCaptor<ExternalHttpRequest> captor = ArgumentCaptor.forClass(ExternalHttpRequest.class);
        verify(httpClient).execute(captor.capture());
        ExternalHttpRequest request = captor.getValue();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.url()).isEqualTo("https://erp.example.com/hook");
        assertThat(new String(request.body(), StandardCharsets.UTF_8)).isEqualTo(PAYLOAD);
        assertThat(request.timeout()).hasSeconds(20);
        Map<String, String> headers = request.headers();
        assertThat(headers.get("Content-Type")).isEqualTo("application/json; charset=utf-8");
        assertThat(headers.get(AiWebhookSignature.HEADER_EVENT)).isEqualTo("RUN.SUCCEEDED");
        assertThat(headers.get(AiWebhookSignature.HEADER_ATTEMPT)).isEqualTo("2");
        assertThat(headers.get(AiWebhookSignature.HEADER_RESOURCE)).isEqualTo("RUN:run_abc");
        assertThat(headers.get(AiWebhookSignature.HEADER_DELIVERY_NO)).isEqualTo("whd_aabb");
        String timestamp = headers.get(AiWebhookSignature.HEADER_TIMESTAMP);
        assertThat(Long.parseLong(timestamp)).isPositive();
        // 签名可被接收端按同一规范校验；密钥本身不出现在任何请求材料里
        assertThat(AiWebhookSignature.verify(
                        SECRET,
                        timestamp,
                        "whd_aabb",
                        request.body(),
                        headers.get(AiWebhookSignature.HEADER_SIGNATURE)))
                .isTrue();
        assertThat(headers.toString()).doesNotContain(SECRET);
        assertThat(headers.keySet()).doesNotContain("Authorization").doesNotContain("Cookie");
        assertThat(outcome.signatureTimestamp()).isEqualTo(Long.parseLong(timestamp));
    }

    @Test
    void disabledOrDeletedTargetsAreNeverCalled() {
        when(targetService.get(TARGET_ID))
                .thenReturn(new AiWebhookTargetDO().setId(TARGET_ID).setStatus(AiWebhookTargetDO.STATUS_DISABLED));

        AiWebhookDeliveryOutcome disabled = sender.deliver(delivery());
        assertThat(disabled.isPermanent()).isTrue();
        assertThat(disabled.errorCode()).isEqualTo(AiWebhookFailureCodes.TARGET_DISABLED);

        when(targetService.get(TARGET_ID))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_WEBHOOK_TARGET_NOT_FOUND));
        AiWebhookDeliveryOutcome deleted = sender.deliver(delivery());
        assertThat(deleted.errorCode()).isEqualTo(AiWebhookFailureCodes.TARGET_DISABLED);

        verify(httpClient, never()).execute(any(ExternalHttpRequest.class));
    }

    @Test
    void missingSigningKeyIsADefiniteFailureWithoutARequest() {
        enableTarget();
        when(targetService.decryptSecret(TARGET_ID)).thenReturn(null);

        AiWebhookDeliveryOutcome outcome = sender.deliver(delivery());

        assertThat(outcome.isPermanent()).isTrue();
        assertThat(outcome.errorCode()).isEqualTo(AiWebhookFailureCodes.SIGNING_KEY_UNAVAILABLE);
        verify(httpClient, never()).execute(any(ExternalHttpRequest.class));
    }

    @Test
    void absentOutboundClientIsRetryableInsteadOfFalselyDelivered() {
        enableTarget();
        when(clientProvider.getIfAvailable()).thenReturn(null);

        AiWebhookDeliveryOutcome outcome = sender.deliver(delivery());

        assertThat(outcome.isRetryable()).isTrue();
        assertThat(outcome.errorCode()).isEqualTo(AiWebhookFailureCodes.INTERNAL_ERROR);
    }

    @Test
    void redirectIsNotFollowedAndIsNeverRetried() {
        enableTargetWithClient();
        when(httpClient.execute(any(ExternalHttpRequest.class))).thenReturn(response(307));

        AiWebhookDeliveryOutcome outcome = sender.deliver(delivery());

        assertThat(outcome.isPermanent()).isTrue();
        assertThat(outcome.errorCode()).isEqualTo(AiWebhookFailureCodes.REDIRECT_NOT_FOLLOWED);
        assertThat(outcome.httpStatus()).isEqualTo(307);
        // 只发一次：不跟随 Location，也不重试
        verify(httpClient).execute(any(ExternalHttpRequest.class));
    }

    @Test
    void clientErrorsArePermanentAndServerErrorsOrRateLimitsAreRetryable() {
        enableTargetWithClient();
        when(httpClient.execute(any(ExternalHttpRequest.class))).thenReturn(response(400));
        assertThat(sender.deliver(delivery()).errorCode()).isEqualTo(AiWebhookFailureCodes.HTTP_CLIENT_ERROR);

        when(httpClient.execute(any(ExternalHttpRequest.class))).thenReturn(response(401));
        assertThat(sender.deliver(delivery()).isPermanent()).isTrue();

        when(httpClient.execute(any(ExternalHttpRequest.class))).thenReturn(response(503));
        AiWebhookDeliveryOutcome serverError = sender.deliver(delivery());
        assertThat(serverError.isRetryable()).isTrue();
        assertThat(serverError.errorCode()).isEqualTo(AiWebhookFailureCodes.HTTP_SERVER_ERROR);

        when(httpClient.execute(any(ExternalHttpRequest.class))).thenReturn(response(429));
        AiWebhookDeliveryOutcome throttled = sender.deliver(delivery());
        assertThat(throttled.isRetryable()).isTrue();
        assertThat(throttled.errorCode()).isEqualTo(AiWebhookFailureCodes.HTTP_RATE_LIMITED);
    }

    @Test
    void boundaryRejectionsMapToStableCodes() {
        enableTargetWithClient();

        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenThrow(new ExternalHttpException(ExternalHttpException.Reason.TARGET_NOT_ALLOWED, "拒"));
        AiWebhookDeliveryOutcome notAllowed = sender.deliver(delivery());
        assertThat(notAllowed.isPermanent()).isTrue();
        assertThat(notAllowed.errorCode()).isEqualTo(AiWebhookFailureCodes.TARGET_NOT_ALLOWED);

        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenThrow(new ExternalHttpException(ExternalHttpException.Reason.PRIVATE_TARGET_DENIED, "拒"));
        assertThat(sender.deliver(delivery()).errorCode()).isEqualTo(AiWebhookFailureCodes.PRIVATE_TARGET_DENIED);

        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenThrow(new ExternalHttpException(ExternalHttpException.Reason.INVALID_REQUEST, "拒"));
        assertThat(sender.deliver(delivery()).isPermanent()).isTrue();

        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenThrow(new ExternalHttpException(ExternalHttpException.Reason.RESPONSE_TOO_LARGE, "拒"));
        assertThat(sender.deliver(delivery()).errorCode()).isEqualTo(AiWebhookFailureCodes.RESPONSE_TOO_LARGE);

        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenThrow(new ExternalHttpException(ExternalHttpException.Reason.TIMEOUT, "超时"));
        AiWebhookDeliveryOutcome timeout = sender.deliver(delivery());
        assertThat(timeout.isRetryable()).isTrue();
        assertThat(timeout.errorCode()).isEqualTo(AiWebhookFailureCodes.TIMEOUT);

        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenThrow(new ExternalHttpException(ExternalHttpException.Reason.CONNECT_FAILED, "连不上"));
        AiWebhookDeliveryOutcome connectFailed = sender.deliver(delivery());
        assertThat(connectFailed.isRetryable()).isTrue();
        assertThat(connectFailed.errorCode()).isEqualTo(AiWebhookFailureCodes.CONNECT_FAILED);
    }

    @Test
    void unexpectedRuntimeFailureIsRetryableWithAStableCodeOnly() {
        enableTargetWithClient();
        when(httpClient.execute(any(ExternalHttpRequest.class)))
                .thenThrow(new IllegalStateException("boom with upstream text"));

        AiWebhookDeliveryOutcome outcome = sender.deliver(delivery());

        assertThat(outcome.isRetryable()).isTrue();
        assertThat(outcome.errorCode()).isEqualTo(AiWebhookFailureCodes.INTERNAL_ERROR);
        assertThat(outcome.toString()).doesNotContain("boom");
    }

    private void enableTarget() {
        when(targetService.get(TARGET_ID))
                .thenReturn(new AiWebhookTargetDO()
                        .setId(TARGET_ID)
                        .setTargetUrl("https://erp.example.com/hook")
                        .setStatus(AiWebhookTargetDO.STATUS_ENABLED));
        when(targetService.decryptSecret(TARGET_ID)).thenReturn(SECRET);
    }

    private void enableTargetWithClient() {
        enableTarget();
        when(clientProvider.getIfAvailable()).thenReturn(httpClient);
    }

    private static ExternalHttpResponse response(int status) {
        return new ExternalHttpResponse(status, Map.of(), new byte[0], 0L);
    }

    private static AiWebhookDeliveryDO delivery() {
        return new AiWebhookDeliveryDO()
                .setId(55L)
                .setTargetId(TARGET_ID)
                .setDeliveryNo("whd_aabb")
                .setEventType("RUN.SUCCEEDED")
                .setResourceType("RUN")
                .setResourceKey("run_abc")
                .setPayloadJson(PAYLOAD)
                .setStatus(AiWebhookDeliveryDO.STATUS_RUNNING)
                .setAttemptCount(2);
    }
}
