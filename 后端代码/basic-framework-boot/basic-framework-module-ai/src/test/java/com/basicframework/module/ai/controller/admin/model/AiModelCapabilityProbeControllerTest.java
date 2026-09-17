package com.basicframework.module.ai.controller.admin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelCapabilityOverviewRespVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelProbeResultRespVO;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.dto.AiModelCapabilityOverviewDTO;
import com.basicframework.module.ai.service.model.dto.AiModelProbeResultDTO;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * M04 探测控制器契约：权限码与 V49 种子一致、响应只含结论与耗时（无凭据字段）、
 * 探测与查询委派到服务层。
 */
class AiModelCapabilityProbeControllerTest {

    private final AiModelCapabilityProbeService probeService = mock(AiModelCapabilityProbeService.class);

    private final AiModelCapabilityProbeController controller = new AiModelCapabilityProbeController(probeService);

    private static AiModelProbeResultDTO result() {
        return new AiModelProbeResultDTO()
                .setProbeKind("EMBEDDING")
                .setStatus("SUPPORTED")
                .setEmbeddingDimension(1536)
                .setLatencyMs(37)
                .setConfigRevision(2);
    }

    @Test
    void probeDelegatesAndMapsConclusions() {
        when(probeService.probeAll(9L)).thenReturn(List.of(result()));

        CommonResult<List<AiModelProbeResultRespVO>> response = controller.probeEndpoint(9L);

        assertThat(response.getData()).singleElement().satisfies(vo -> {
            assertThat(vo.getProbeKind()).isEqualTo("EMBEDDING");
            assertThat(vo.getStatus()).isEqualTo("SUPPORTED");
            assertThat(vo.getEmbeddingDimension()).isEqualTo(1536);
            assertThat(vo.getLatencyMs()).isEqualTo(37);
            assertThat(vo.getConfigRevision()).isEqualTo(2);
        });
        verify(probeService).probeAll(9L);
    }

    @Test
    void latestResultsAndOverviewDelegate() {
        when(probeService.getLatestResults(9L)).thenReturn(List.of(result()));
        when(probeService.getCapabilityOverview(9L))
                .thenReturn(new AiModelCapabilityOverviewDTO()
                        .setEndpointId(9L)
                        .setDeclared(List.of("TEXT", "EMBEDDING"))
                        .setSupported(List.of("TEXT"))
                        .setPublishable(List.of("TEXT")));

        assertThat(controller.getLatestProbeResults(9L).getData()).hasSize(1);
        AiModelCapabilityOverviewRespVO overview =
                controller.getCapabilityOverview(9L).getData();
        assertThat(overview.getEndpointId()).isEqualTo(9L);
        assertThat(overview.getPublishable()).containsExactly("TEXT");
        verify(probeService).getLatestResults(9L);
        verify(probeService).getCapabilityOverview(9L);
    }

    @Test
    void probeRequiresDedicatedPermissionAndQueriesReuseQueryPermission() throws Exception {
        assertThat(permissionOf("probeEndpoint")).isEqualTo("ai:model-endpoint:probe");
        assertThat(permissionOf("getLatestProbeResults")).isEqualTo("ai:model-endpoint:query");
        assertThat(permissionOf("getCapabilityOverview")).isEqualTo("ai:model-endpoint:query");
    }

    @Test
    void responseModelNeverExposesCredentials() {
        Set<String> fieldNames = Arrays.stream(AiModelProbeResultRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(fieldNames)
                .containsExactlyInAnyOrder(
                        "probeKind", "status", "detailCode", "embeddingDimension", "latencyMs", "configRevision");
    }

    private static String permissionOf(String methodName) throws Exception {
        PreAuthorize annotation = AiModelCapabilityProbeController.class
                .getMethod(methodName, Long.class)
                .getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }
}
