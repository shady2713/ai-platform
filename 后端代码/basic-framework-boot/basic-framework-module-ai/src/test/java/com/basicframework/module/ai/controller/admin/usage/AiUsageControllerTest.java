package com.basicframework.module.ai.controller.admin.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.usage.vo.AiUsagePageReqVO;
import com.basicframework.module.ai.controller.admin.usage.vo.AiUsageRespVO;
import com.basicframework.module.ai.dal.dataobject.usage.AiUsageLedgerDO;
import com.basicframework.module.ai.service.quota.AiQuotaService;
import com.basicframework.module.ai.service.usage.AiUsageLedgerService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

/** Q02 用量控制面契约：权限码与 V81 种子一致、响应不含秘密与正文、端点唯一鉴权策略。 */
class AiUsageControllerTest {

    private final AiUsageLedgerService usageService = mock(AiUsageLedgerService.class);

    private final AiQuotaService quotaService = mock(AiQuotaService.class);

    private final AiUsageController controller = new AiUsageController(usageService, quotaService);

    private static AiUsageLedgerDO row() {
        return new AiUsageLedgerDO()
                .setApplicationId(1L)
                .setDurationMs(12)
                .setEndpointRef("endpoint:3")
                .setInputTokens(null)
                .setInvocationId("inv_1")
                .setModelRef("qwen-plus")
                .setModelRevision(2)
                .setOccurredAt(LocalDateTime.now())
                .setOutputTokens(null)
                .setRunId(10L)
                .setServiceId(4L)
                .setStatus("SUCCEEDED")
                .setUsageSource("UNKNOWN");
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiUsageController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }

    @Test
    void everyEndpointDeclaresExactlyOnePermissionPolicy() {
        int endpoints = 0;
        for (Method method : AiUsageController.class.getDeclaredMethods()) {
            if (method.getAnnotation(GetMapping.class) == null) {
                continue;
            }
            endpoints++;
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            assertThat(annotation)
                    .as("%s 必须且只能声明一个 @PreAuthorize 权限", method.getName())
                    .isNotNull();
            assertThat(annotation.value()).contains("@ss.hasPermission('ai:usage:query')");
        }
        assertThat(endpoints).as("端点数量与 V81 菜单种子一致").isEqualTo(4);
    }

    @Test
    void permissionsMatchMigrationSeeds() throws Exception {
        assertThat(permissionOf("page", AiUsagePageReqVO.class)).isEqualTo("ai:usage:query");
        assertThat(permissionOf("summary", Long.class, LocalDateTime.class, LocalDateTime.class))
                .isEqualTo("ai:usage:query");
        assertThat(permissionOf("serviceSummary", Long.class, LocalDateTime.class, LocalDateTime.class))
                .isEqualTo("ai:usage:query");
        assertThat(permissionOf("quotaActive", Long.class)).isEqualTo("ai:usage:query");
    }

    @Test
    void responsesNeverCarrySecretsOrPromptContent() {
        List<String> fields = Arrays.stream(AiUsageRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertThat(fields)
                .contains("inputTokens", "outputTokens", "usageSource", "endpointRef")
                .noneMatch(name -> name.matches("(?i).*(credential|password|secret|apikey|ciphertext|jdbc).*"))
                .doesNotContain("token", "prompt", "question", "message", "content", "sql", "endpointUrl");
    }

    @Test
    void delegatesToServiceWithConvertedArguments() {
        when(usageService.page(any(PageParam.class), any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(row()), 1L));
        when(usageService.summaryBySource(anyLong(), any(), any())).thenReturn(Map.of("unknownInvocations", 2L));
        when(usageService.summaryByService(anyLong(), any(), any())).thenReturn(List.of(Map.of("serviceId", 4L)));
        when(quotaService.activeCount(1L)).thenReturn(3L);
        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now();

        AiUsageRespVO respVO = controller
                .page(new AiUsagePageReqVO().setApplicationId(1L))
                .getData()
                .getList()
                .get(0);
        assertThat(respVO.getInvocationId()).isEqualTo("inv_1");
        assertThat(respVO.getUsageSource()).isEqualTo("UNKNOWN");
        // token 未知时保持为空（读侧据此显示"未知"，不显示 0）
        assertThat(respVO.getInputTokens()).isNull();
        assertThat(respVO.getOutputTokens()).isNull();

        assertThat(controller.summary(1L, from, to).getData()).containsEntry("unknownInvocations", 2L);
        assertThat(controller.serviceSummary(1L, from, to).getData()).hasSize(1);
        assertThat(controller.quotaActive(1L).getData()).isEqualTo(3L);
    }
}
