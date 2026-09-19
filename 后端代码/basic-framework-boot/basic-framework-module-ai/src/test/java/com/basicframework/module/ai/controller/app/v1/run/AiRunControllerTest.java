package com.basicframework.module.ai.controller.app.v1.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.run.vo.AiRunAcceptReqVO;
import com.basicframework.module.ai.controller.app.v1.run.vo.AiRunAcceptRespVO;
import com.basicframework.module.ai.controller.app.v1.run.vo.AiRunCancelReqVO;
import com.basicframework.module.ai.controller.app.v1.run.vo.AiRunPageReqVO;
import com.basicframework.module.ai.controller.app.v1.run.vo.AiRunRespVO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.service.event.AiRunEventService;
import com.basicframework.module.ai.service.event.dto.AiRunEventDTO;
import com.basicframework.module.ai.service.event.dto.AiRunEventSnapshotDTO;
import com.basicframework.module.ai.service.run.AiRunService;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptDTO;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptResultDTO;
import jakarta.annotation.security.PermitAll;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** O02 运行受理控制面契约：每个端点有且只有一种鉴权策略，响应不含正文与秘密。 */
class AiRunControllerTest {

    private final AiRunService runService = mock(AiRunService.class);

    private final AiRunEventService eventService = mock(AiRunEventService.class);

    private final AiRunController controller = new AiRunController(runService, eventService);

    private static AiRunDO run() {
        return new AiRunDO()
                .setId(41L)
                .setRunKey("run_0123456789abcdef01234567")
                .setConversationId(31L)
                .setServiceId(9L)
                .setReleaseId(21L)
                .setContentHash("a".repeat(64))
                .setEndpointConfigRevision(3)
                .setStatus(AiRunDO.STATUS_ACCEPTED)
                .setStepCount(0)
                .setVersion(0);
    }

    @Test
    void everyEndpointDeclaresExactlyOneAuthenticationStrategy() {
        for (Method method : AiRunController.class.getDeclaredMethods()) {
            if (method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) == null
                    && method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) == null) {
                continue;
            }
            int strategies = 0;
            strategies += method.isAnnotationPresent(AuthenticatedOnly.class) ? 1 : 0;
            strategies += method.isAnnotationPresent(PermitAll.class) ? 1 : 0;
            strategies += method.isAnnotationPresent(PreAuthorize.class) ? 1 : 0;
            assertThat(strategies).as("%s 必须且只能声明一种鉴权策略", method.getName()).isEqualTo(1);
            assertThat(method.isAnnotationPresent(AuthenticatedOnly.class)).isTrue();
        }
    }

    @Test
    void acceptDelegatesAndReturnsRunReferenceOnly() {
        when(runService.accept(any()))
                .thenReturn(new AiRunAcceptResultDTO()
                        .setRunId(41L)
                        .setRunKey("run_0123456789abcdef01234567")
                        .setStatus(AiRunDO.STATUS_ACCEPTED)
                        .setReleaseId(21L)
                        .setReleaseVersion(2)
                        .setReused(true));

        AiRunAcceptRespVO respVO = controller
                .accept(new AiRunAcceptReqVO()
                        .setServiceId(9L)
                        .setConversationId(31L)
                        .setIdempotencyKey("idem-0123456789abcdef")
                        .setMessage("帮我查订单")
                        .setAttachmentKeys(List.of("file-a"))
                        .setBusinessContext("{\"page\":\"order\"}")
                        .setDataLevel("L2_INTERNAL"))
                .getData();

        verify(runService)
                .accept(new AiRunAcceptDTO()
                        .setServiceId(9L)
                        .setConversationId(31L)
                        .setIdempotencyKey("idem-0123456789abcdef")
                        .setMessage("帮我查订单")
                        .setAttachmentKeys(List.of("file-a"))
                        .setBusinessContext("{\"page\":\"order\"}")
                        .setDataLevel("L2_INTERNAL"));
        assertThat(respVO.getRunId()).isEqualTo(41L);
        assertThat(respVO.getStatus()).isEqualTo(AiRunDO.STATUS_ACCEPTED);
        assertThat(respVO.getReleaseVersion()).isEqualTo(2);
        assertThat(respVO.isReused()).isTrue();
    }

    @Test
    void responsesNeverCarryRequestTextOrSecrets() {
        List<String> acceptFields = Arrays.stream(AiRunAcceptRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        List<String> runFields = Arrays.stream(AiRunRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(acceptFields)
                .as("受理结果不得回显请求正文、幂等键或凭据")
                .doesNotContain("message", "businessContext", "idempotencyKey", "credential", "token", "inputDigest");
        assertThat(runFields)
                .as("运行读取不得回显请求摘要或凭据")
                .doesNotContain("inputDigest", "credential", "token", "message", "businessContext");
    }

    @Test
    void eventsEndpointAuthenticatesBeforeOpeningTheStreamAndReplays() throws Exception {
        when(eventService.snapshot(41L))
                .thenReturn(new AiRunEventSnapshotDTO()
                        .setRunId(41L)
                        .setRunKey("run_0123456789abcdef01234567")
                        .setStatus(AiRunDO.STATUS_RUNNING)
                        .setLatestSeq(2)
                        .setEarliestSeq(1));
        when(eventService.replay(41L, 0, 200))
                .thenReturn(List.of(new AiRunEventDTO()
                        .setSchemaVersion("1.0")
                        .setSeq(1)
                        .setRunId("run_0123456789abcdef01234567")
                        .setStatus(AiRunDO.STATUS_RUNNING)));

        var emitter = controller.events(41L, 0);

        assertThat(emitter).isNotNull();
        // 归属判定在开流之前完成：快照先读，事件按 afterSeq 重放
        verify(eventService).snapshot(41L);
        verify(eventService).replay(41L, 0, 200);
    }

    @Test
    void eventsEndpointCompletesImmediatelyForTerminalRuns() throws Exception {
        when(eventService.snapshot(41L))
                .thenReturn(new AiRunEventSnapshotDTO()
                        .setRunId(41L)
                        .setRunKey("run_0123456789abcdef01234567")
                        .setStatus(AiRunDO.STATUS_SUCCEEDED)
                        .setLatestSeq(3)
                        .setEarliestSeq(1));
        when(eventService.replay(41L, 3, 200)).thenReturn(List.of());

        assertThat(controller.events(41L, 3)).isNotNull();
        verify(eventService).replay(41L, 3, 200);
    }

    @Test
    void eventsEndpointClosesTheStreamOnReplayFailureInsteadOfLeakingAnAnonymousStream() throws Exception {
        when(eventService.snapshot(41L))
                .thenReturn(new AiRunEventSnapshotDTO()
                        .setRunId(41L)
                        .setRunKey("run_0123456789abcdef01234567")
                        .setStatus(AiRunDO.STATUS_RUNNING)
                        .setLatestSeq(3)
                        .setEarliestSeq(3));
        // 重放窗口过期（或连接已断开）：开流之后不再改变 HTTP 状态，而是关闭订阅
        when(eventService.replay(41L, 1, 200))
                .thenThrow(com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception(
                        com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_EVENT_WINDOW_EXPIRED));

        var emitter = controller.events(41L, 1);

        assertThat(emitter).isNotNull();
        verify(eventService).replay(41L, 1, 200);
    }

    @Test
    void cancelDelegatesWithOptimisticLockVersion() {
        controller.cancel(new AiRunCancelReqVO().setRunId(41L).setVersion(2));
        verify(eventService).cancel(41L, 2);
    }

    @Test
    void readsMapRunStateAndPageHasNoOwnershipFields() {
        when(runService.getRun(41L)).thenReturn(run());
        AiRunRespVO respVO = controller.get(41L).getData();
        assertThat(respVO.getRunKey()).isEqualTo("run_0123456789abcdef01234567");
        assertThat(respVO.getReleaseId()).isEqualTo(21L);
        assertThat(respVO.getStatus()).isEqualTo(AiRunDO.STATUS_ACCEPTED);

        when(runService.getPage(any())).thenReturn(new PageResult<>(List.of(run()), 1L));
        assertThat(controller.page(new AiRunPageReqVO()).getData().getList()).hasSize(1);

        assertThat(AiRunPageReqVO.class.getDeclaredFields())
                .as("归属只来自服务端身份：分页请求不允许携带应用/主体条件")
                .isEmpty();
    }
}
