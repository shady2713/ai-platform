package com.basicframework.module.ai.controller.admin.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookDeliveryAttemptRespVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookDeliveryPageReqVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookDeliveryRedeliverReqVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookDeliveryRespVO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryAttemptDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryDO;
import com.basicframework.module.ai.service.webhook.AiWebhookDeliveryService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

/** Webhook 投递控制面契约（X10）：权限码与 V88 种子一致、响应不含投递正文与目标地址。 */
class AiWebhookDeliveryControllerTest {

    private final AiWebhookDeliveryService deliveryService = mock(AiWebhookDeliveryService.class);

    private final AiWebhookDeliveryController controller = new AiWebhookDeliveryController(deliveryService);

    @Test
    void everyEndpointDeclaresItsPermissionPolicy() throws Exception {
        assertThat(permissionOf("getDelivery", Long.class)).isEqualTo("ai:webhook:query");
        assertThat(permissionOf("getDeliveryPage", AiWebhookDeliveryPageReqVO.class))
                .isEqualTo("ai:webhook:query");
        assertThat(permissionOf("getAttempts", Long.class)).isEqualTo("ai:webhook:query");
        assertThat(permissionOf("redeliver", AiWebhookDeliveryRedeliverReqVO.class))
                .isEqualTo("ai:webhook:redeliver");

        for (Method method : AiWebhookDeliveryController.class.getDeclaredMethods()) {
            if (method.getAnnotation(GetMapping.class) == null && method.getAnnotation(PutMapping.class) == null) {
                continue;
            }
            assertThat(permissionOf(method.getName(), method.getParameterTypes()))
                    .as("%s 必须有权限码", method.getName())
                    .startsWith("ai:webhook:");
        }
    }

    @Test
    void deliveryResponseCarriesFactsButNotThePayloadOrTargetUrl() throws Exception {
        when(deliveryService.get(55L)).thenReturn(delivery());
        when(deliveryService.getAttempts(55L)).thenReturn(List.of(attempt(1), attempt(2)));

        AiWebhookDeliveryRespVO vo = controller.getDelivery(55L).getData();
        assertThat(vo.getDeliveryNo()).isEqualTo("whd_aabb");
        assertThat(vo.getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_FAILED);
        assertThat(vo.getAttemptCount()).isEqualTo(2);
        assertThat(vo.getMaxAttempts()).isEqualTo(3);
        assertThat(vo.getFailureCode()).isEqualTo("1_003_011_006");
        assertThat(vo.getLastErrorCode()).isEqualTo("timeout");
        assertThat(vo.getPayloadDigest()).hasSize(64);
        assertThat(vo.getOccurredTime()).isEqualTo(LocalDateTime.of(2026, 9, 27, 9, 0, 0));

        List<AiWebhookDeliveryAttemptRespVO> attempts =
                controller.getAttempts(55L).getData();
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(1).getAttemptNo()).isEqualTo(2);
        assertThat(attempts.get(1).getOutcome()).isEqualTo(AiWebhookDeliveryAttemptDO.OUTCOME_RETRYABLE);

        // 响应模型里没有投递正文与目标地址字段（正文只以摘要形式出现）
        List<String> fieldNames = Arrays.stream(AiWebhookDeliveryRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertThat(fieldNames)
                .doesNotContain("payloadJson")
                .doesNotContain("targetUrl")
                .doesNotContain("leaseOwner");
    }

    @Test
    void pagePassesFiltersThroughAndMapsRows() {
        when(deliveryService.getPage(any(), eq(91L), eq("FAILED"), eq("RUN.SUCCEEDED")))
                .thenReturn(new PageResult<>(List.of(delivery()), 1L));

        AiWebhookDeliveryPageReqVO pageReqVO = new AiWebhookDeliveryPageReqVO();
        pageReqVO.setTargetId(91L);
        pageReqVO.setStatus("FAILED");
        pageReqVO.setEventType("RUN.SUCCEEDED");

        PageResult<AiWebhookDeliveryRespVO> page =
                controller.getDeliveryPage(pageReqVO).getData();

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList().get(0).getResourceKey()).isEqualTo("run_abc");
    }

    @Test
    void redeliverOnlyTakesTheIdAndReturnsSuccess() {
        assertThat(controller
                        .redeliver(new AiWebhookDeliveryRedeliverReqVO().setId(55L))
                        .getData())
                .isTrue();
        verify(deliveryService).redeliver(55L);
    }

    private static AiWebhookDeliveryDO delivery() {
        return new AiWebhookDeliveryDO()
                .setId(55L)
                .setDeliveryNo("whd_aabb")
                .setTargetId(91L)
                .setApplicationId(7L)
                .setEventType("RUN.SUCCEEDED")
                .setResourceType("RUN")
                .setResourceId(12L)
                .setResourceKey("run_abc")
                .setOccurredTime(LocalDateTime.of(2026, 9, 27, 9, 0, 0))
                .setPayloadJson("{\"schemaVersion\":\"1.0\"}")
                .setPayloadDigest("a".repeat(64))
                .setStatus(AiWebhookDeliveryDO.STATUS_FAILED)
                .setAttemptCount(2)
                .setMaxAttempts(3)
                .setLastErrorCode("timeout")
                .setFailureCode("1_003_011_006")
                .setNextAttemptTime(LocalDateTime.of(2026, 9, 27, 9, 1, 0))
                .setFirstAttemptTime(LocalDateTime.of(2026, 9, 27, 9, 0, 0))
                .setVersion(3);
    }

    private static AiWebhookDeliveryAttemptDO attempt(int attemptNo) {
        return new AiWebhookDeliveryAttemptDO()
                .setDeliveryId(55L)
                .setAttemptNo(attemptNo)
                .setOutcome(AiWebhookDeliveryAttemptDO.OUTCOME_RETRYABLE)
                .setErrorCode("timeout")
                .setSignatureTimestamp(1_790_000_000L)
                .setDurationMs(12L)
                .setStartedTime(LocalDateTime.of(2026, 9, 27, 9, 0, 0))
                .setFinishedTime(LocalDateTime.of(2026, 9, 27, 9, 0, 0));
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiWebhookDeliveryController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }
}
