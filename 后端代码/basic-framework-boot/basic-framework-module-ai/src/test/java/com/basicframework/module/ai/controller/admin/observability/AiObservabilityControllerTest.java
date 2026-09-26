package com.basicframework.module.ai.controller.admin.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunMonitorDetailRespVO;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunMonitorPageReqVO;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunMonitorRespVO;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunRetryReqVO;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunTimelineRespVO;
import com.basicframework.module.ai.dal.dataobject.event.AiRunEventDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.dataobject.usage.AiUsageLedgerDO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** Q03 运行监控契约：权限点与 V82 种子一致、响应不含秘密/正文、可重试性由同一判据驱动。 */
class AiObservabilityControllerTest {

    private final AiRunMonitorQuery monitorQuery = mock(AiRunMonitorQuery.class);

    private final AiRunRetryCommand retryCommand = mock(AiRunRetryCommand.class);

    private final AiObservabilityController controller = new AiObservabilityController(monitorQuery, retryCommand);

    private static AiRunDO run(String status) {
        AiRunDO run = new AiRunDO()
                .setApplicationId(1L)
                .setContentHash("a".repeat(64))
                .setDataLevel("L2_INTERNAL")
                .setEndpointConfigRevision(2)
                .setEventSeq(3)
                .setId(21L)
                .setModelEndpointId(3L)
                .setReleaseId(7L)
                .setRunKey("run_abc")
                .setServiceId(4L)
                .setStatus(status)
                .setStepCount(2)
                .setSubjectType("USER")
                .setVersion(6);
        run.setCreateTime(LocalDateTime.now().minusMinutes(1));
        run.setUpdateTime(LocalDateTime.now());
        return run;
    }

    private static AiRunTaskDO task(String status) {
        return new AiRunTaskDO()
                .setId(33L)
                .setRunId(21L)
                .setTaskKind(AiRunTaskDO.KIND_RUN_STEP)
                .setStatus(status)
                .setAttemptCount(3)
                .setLastErrorCode("STEP_BUDGET_EXCEEDED")
                .setVersion(1);
    }

    private static AiRunEventDO event() {
        AiRunEventDO event = new AiRunEventDO()
                .setBlockJson("{\"rows\":1}")
                .setBlockType("table")
                .setRunId(21L)
                .setSchemaVersion("1.0")
                .setSeq(1)
                .setStatus("SUCCEEDED");
        event.setCreateTime(LocalDateTime.now());
        return event;
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiObservabilityController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }

    @Test
    void everyEndpointDeclaresExactlyOnePermissionPolicy() throws Exception {
        int reads = 0;
        for (Method method : AiObservabilityController.class.getDeclaredMethods()) {
            if (method.getAnnotation(GetMapping.class) == null) {
                continue;
            }
            reads++;
            assertThat(permissionOf(method.getName(), method.getParameterTypes()))
                    .as("%s 的查看权限", method.getName())
                    .isEqualTo("ai:observability:query");
        }
        assertThat(reads).as("三个只读端点").isEqualTo(3);
        assertThat(permissionOf("retry", AiRunRetryReqVO.class))
                .as("重试是独立权限点（看与做分别鉴权）")
                .isEqualTo("ai:observability:retry");
        assertThat(AiObservabilityController.class
                        .getMethod("retry", AiRunRetryReqVO.class)
                        .getAnnotation(PostMapping.class))
                .isNotNull();
    }

    @Test
    void detailNeverExposesCredentialsPromptsOrSubjectIdentity() {
        List<Class<?>> protocolTypes = List.of(
                AiRunMonitorRespVO.class,
                AiRunMonitorDetailRespVO.class,
                AiRunTimelineRespVO.class,
                AiRunRetryReqVO.class,
                AiRunMonitorPageReqVO.class);
        for (Class<?> type : protocolTypes) {
            for (Field field : type.getDeclaredFields()) {
                String name = field.getName().toLowerCase();
                assertThat(name)
                        .as("%s.%s 不得携带凭据/正文/外部主体标识", type.getSimpleName(), field.getName())
                        .doesNotContain("secret")
                        .doesNotContain("credential")
                        .doesNotContain("prompt")
                        .doesNotContain("externaluser")
                        .doesNotContain("blockjson");
            }
        }
        assertThat(AiRunMonitorDetailRespVO.class.getDeclaredFields())
                .extracting(Field::getName)
                .contains("contentHash", "endpointConfigRevision", "resultDigest")
                .doesNotContain("inputDigest", "outputText");
    }

    @Test
    void listRowsReuseTheSameRetryabilityRule() {
        when(monitorQuery.pageRuns(any())).thenReturn(new PageResult<>(List.of(run(AiRunDO.STATUS_FAILED)), 1L));
        when(monitorQuery.findStepTasks(any())).thenReturn(Map.of(21L, task(AiRunTaskDO.STATUS_FAILED)));

        AiRunMonitorRespVO retryable = controller
                .page(new AiRunMonitorPageReqVO().setApplicationId(1L))
                .getData()
                .getList()
                .get(0);
        assertThat(retryable.isRetryable()).isTrue();
        assertThat(retryable.getRetryBlockedReason()).isNull();
        assertThat(retryable.getLastErrorCode()).isEqualTo("STEP_BUDGET_EXCEEDED");

        when(monitorQuery.findStepTasks(any())).thenReturn(Map.of(21L, task(AiRunTaskDO.STATUS_UNKNOWN)));
        AiRunMonitorRespVO blocked = controller
                .page(new AiRunMonitorPageReqVO().setApplicationId(1L))
                .getData()
                .getList()
                .get(0);
        assertThat(blocked.isRetryable()).isFalse();
        assertThat(blocked.getRetryBlockedReason()).contains("结果未知");
    }

    @Test
    void detailAssemblesMeasuredTimingAndResultReference() {
        AiRunDO run = run(AiRunDO.STATUS_SUCCEEDED);
        when(monitorQuery.requireRun(21L)).thenReturn(run);
        when(monitorQuery.findStepTask(21L)).thenReturn(task(AiRunTaskDO.STATUS_SUCCEEDED));
        when(monitorQuery.usages(21L))
                .thenReturn(List.of(
                        new AiUsageLedgerDO().setDurationMs(700).setUsageSource(AiUsageLedgerDO.SOURCE_REPORTED)));

        AiRunMonitorDetailRespVO detail = controller.get(21L).getData();

        assertThat(detail.getRunVersion()).isEqualTo(6);
        assertThat(detail.getTiming().getModelDurationMs()).isEqualTo(700L);
        assertThat(detail.getTiming().getUnmeasuredStages()).containsExactly("RETRIEVAL", "BUSINESS_API");
        assertThat(detail.getTaskStatus()).isEqualTo(AiRunTaskDO.STATUS_SUCCEEDED);
        assertThat(detail.isRetryable()).as("任务已成功不可重试").isFalse();
        assertThat(detail.getResultMessageId()).isNull();
        assertThat(detail.getSubjectType()).isEqualTo("USER");
    }

    @Test
    void timelineMarksBlockPresenceWithoutReturningTheBlockBody() {
        when(monitorQuery.timeline(anyLong(), any(), any())).thenReturn(List.of(event()));
        when(monitorQuery.requireRun(21L)).thenReturn(run(AiRunDO.STATUS_SUCCEEDED));

        List<AiRunTimelineRespVO> events = controller.timeline(21L, null, 100).getData();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).isBlockPresent()).isTrue();
        assertThat(events.get(0).getBlockType()).isEqualTo("table");
        assertThat(AiRunTimelineRespVO.class.getDeclaredFields())
                .extracting(Field::getName)
                .doesNotContain("blockJson");
    }

    @Test
    void retryDelegatesWithTheVersionFromTheClient() {
        controller.retry(new AiRunRetryReqVO().setRunId(21L).setVersion(6));

        verify(retryCommand).retry(21L, 6);
        verify(monitorQuery, never()).pageRuns(any());
    }
}
