package com.basicframework.module.ai.controller.admin.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.controller.admin.query.vo.AiQueryPlanReqVO;
import com.basicframework.module.ai.controller.admin.query.vo.AiQueryPlanRespVO;
import com.basicframework.module.ai.service.query.planner.AiQueryPlanner;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanResultDTO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** D05 查询规划控制面契约：权限码与 V69 种子一致、响应不含凭据与上游数据。 */
class AiQueryControllerTest {

    private final AiQueryPlanner queryPlanner = mock(AiQueryPlanner.class);

    private final AiQueryController controller = new AiQueryController(queryPlanner);

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiQueryController.class.getMethod(methodName, parameterTypes);
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
        for (Method method : AiQueryController.class.getDeclaredMethods()) {
            boolean endpoint = method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) != null
                    || method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) != null;
            if (!endpoint) {
                continue;
            }
            endpoints++;
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            assertThat(annotation)
                    .as("%s 必须且只能声明一个 @PreAuthorize 权限", method.getName())
                    .isNotNull();
            assertThat(annotation.value()).contains("@ss.hasPermission('ai:query:");
        }
        assertThat(endpoints).as("端点数量与 V69 菜单种子一致").isEqualTo(2);
    }

    @Test
    void permissionsMatchMigrationSeeds() throws Exception {
        assertThat(permissionOf("plan", AiQueryPlanReqVO.class)).isEqualTo("ai:query:plan");
        assertThat(permissionOf("summary", Long.class, Long.class, List.class)).isEqualTo("ai:query:summary");
    }

    @Test
    void responsesNeverCarryCredentialsOrUpstreamRows() {
        for (Class<?> voType : List.of(AiQueryPlanRespVO.class, AiQueryPlanRespVO.Candidate.class)) {
            List<String> fields = Arrays.stream(voType.getDeclaredFields())
                    .map(Field::getName)
                    .toList();
            assertThat(fields)
                    .as("%s 不得出现凭据/连接串/行数据字段", voType.getSimpleName())
                    .noneMatch(name -> name.matches("(?i).*(credential|password|secret|token|ciphertext|jdbc|rows).*"));
        }
    }

    @Test
    void delegatesToPlannerWithConvertedArguments() {
        when(queryPlanner.plan(any()))
                .thenReturn(new AiQueryPlanResultDTO()
                        .setKind("PLAN")
                        .setDatasetId(81L)
                        .setDatasetCode("it-query-orders")
                        .setPlanDatasetId("dset_it-query-orders")
                        .setDatasetVersionId(91L)
                        .setDatasetVersionNo(1)
                        .setSchemaHash("a".repeat(64))
                        .setPlanHash("b".repeat(64))
                        .setPlanJson("{\"limit\":10}")
                        .setAttempts(1));
        when(queryPlanner.datasetSummary(anyLong(), any(), any())).thenReturn("{\"datasetId\":\"dset_x\"}");

        AiQueryPlanRespVO plan = controller
                .plan(new AiQueryPlanReqVO()
                        .setDatasetId(81L)
                        .setEndpointId(51L)
                        .setQuestion("上个月华东的销售额"))
                .getData();
        assertThat(plan.getKind()).isEqualTo("PLAN");
        assertThat(plan.getPlanHash()).isEqualTo("b".repeat(64));
        assertThat(plan.getCandidates()).isEmpty();

        assertThat(controller
                        .summary(81L, null, List.of("net_amount"))
                        .getData()
                        .getSummaryJson())
                .contains("dset_x");
        verify(queryPlanner).plan(any());
        verify(queryPlanner).datasetSummary(anyLong(), any(), any());
    }
}
