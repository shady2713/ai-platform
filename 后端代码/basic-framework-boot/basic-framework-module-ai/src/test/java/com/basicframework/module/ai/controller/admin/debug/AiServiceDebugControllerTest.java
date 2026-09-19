package com.basicframework.module.ai.controller.admin.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.controller.admin.debug.vo.AiServiceDebugResultRespVO;
import com.basicframework.module.ai.controller.admin.debug.vo.AiServiceDebugRunReqVO;
import com.basicframework.module.ai.domain.runtime.AiContextSection;
import com.basicframework.module.ai.service.context.dto.AiContextSectionStatDTO;
import com.basicframework.module.ai.service.debug.AiServiceDebugService;
import com.basicframework.module.ai.service.debug.dto.AiDebugStageDTO;
import com.basicframework.module.ai.service.debug.dto.AiServiceDebugResultDTO;
import com.basicframework.module.ai.service.debug.dto.AiServiceDebugRunDTO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;

/** S04 调试控制面契约：权限码与 V58 种子一致、响应只含阶段摘要与证据。 */
class AiServiceDebugControllerTest {

    private final AiServiceDebugService debugService = mock(AiServiceDebugService.class);

    private final AiServiceDebugController controller = new AiServiceDebugController(debugService);

    @Test
    void delegatesDebugRunWithExplicitSubject() {
        when(debugService.debugRun(any()))
                .thenReturn(new AiServiceDebugResultDTO()
                        .setReleaseId(21L)
                        .setReleaseVersion(2)
                        .setContentHash("a".repeat(64))
                        .setModelEndpointId(3L)
                        .setModelRevision(7)
                        .setTestSubjectType("USER")
                        .setTestSubjectId("u-1001")
                        .setAuthorizedBindings(List.of("REPORT:report-1"))
                        .setSections(List.of(new AiContextSectionStatDTO()
                                .setSection(AiContextSection.POLICY)
                                .setIncludedCount(1)
                                .setEstimatedTokens(35)
                                .setSanitized(true)))
                        .setEstimatedTokens(60)
                        .setTruncated(false)
                        .setStages(List.of(new AiDebugStageDTO()
                                .setStage("MODEL")
                                .setStatus("OK")
                                .setDetail("endpoint=3")
                                .setDurationMs(12L)))
                        .setOutput("订单 A-1 已发货")
                        .setStructured(false)
                        .setInputTokens(50)
                        .setOutputTokens(20)
                        .setDurationMs(30L));

        AiServiceDebugResultRespVO respVO = controller
                .run(new AiServiceDebugRunReqVO()
                        .setServiceId(9L)
                        .setTestSubjectType("USER")
                        .setTestSubjectId("u-1001")
                        .setUserMessage("帮我查一下订单 A-1")
                        .setBusinessContext("{\"page\":\"order\"}")
                        .setDataLevel("L2_INTERNAL")
                        .setMaxTokens(4000)
                        .setHistory(List.of(new AiServiceDebugRunReqVO.HistoryItem()
                                .setRole("user")
                                .setContent("你好"))))
                .getData();

        ArgumentCaptor<AiServiceDebugRunDTO> captor = ArgumentCaptor.forClass(AiServiceDebugRunDTO.class);
        verify(debugService).debugRun(captor.capture());
        assertThat(captor.getValue().getTestSubjectType()).isEqualTo("USER");
        assertThat(captor.getValue().getTestSubjectId()).isEqualTo("u-1001");
        assertThat(captor.getValue().getDataLevel()).isEqualTo("L2_INTERNAL");
        assertThat(captor.getValue().getMaxTokens()).isEqualTo(4000);
        assertThat(captor.getValue().getHistory()).singleElement().satisfies(item -> assertThat(item.getContent())
                .isEqualTo("你好"));

        assertThat(respVO.getReleaseVersion()).isEqualTo(2);
        assertThat(respVO.getModelRevision()).isEqualTo(7);
        assertThat(respVO.getAuthorizedBindings()).containsExactly("REPORT:report-1");
        assertThat(respVO.getSections()).singleElement().satisfies(section -> {
            assertThat(section.getSection()).isEqualTo("POLICY");
            assertThat(section.getSanitized()).isTrue();
        });
        assertThat(respVO.getStages()).singleElement().satisfies(stage -> {
            assertThat(stage.getStage()).isEqualTo("MODEL");
            assertThat(stage.getDurationMs()).isEqualTo(12L);
        });
        assertThat(respVO.getOutput()).isEqualTo("订单 A-1 已发货");
        assertThat(respVO.getInputTokens()).isEqualTo(50);
    }

    @Test
    void responseNeverCarriesTheAssembledPrompt() {
        List<String> fieldNames = Arrays.stream(AiServiceDebugResultRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fieldNames)
                .as("调试响应不得回显拼装后的提示词或隐藏推理")
                .doesNotContain("prompt", "systemPrompt", "reasoning", "hiddenReasoning");
    }

    @Test
    void permissionsMatchMigrationSeeds() throws Exception {
        Method method = AiServiceDebugController.class.getMethod("run", AiServiceDebugRunReqVO.class);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("调试接口必须声明服务端权限表达式").isNotNull();
        assertThat(annotation
                        .value()
                        .replace("@ss.hasPermission(", "")
                        .replace(")", "")
                        .replace("'", ""))
                .isEqualTo("ai:service:debug");
    }
}
