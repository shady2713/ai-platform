package com.basicframework.module.ai.controller.app.v1.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.task.vo.AiTaskPageReqVO;
import com.basicframework.module.ai.controller.app.v1.task.vo.AiTaskProgressRespVO;
import com.basicframework.module.ai.controller.app.v1.task.vo.AiTaskRetryReqVO;
import com.basicframework.module.ai.service.task.AiTaskService;
import com.basicframework.module.ai.service.task.dto.AiRunProgressDTO;
import jakarta.annotation.security.PermitAll;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** O06 任务查询与重试控制面契约：每个端点有且只有一种鉴权策略，结果只给标识与摘要。 */
class AiTaskControllerTest {

    private final AiTaskService taskService = mock(AiTaskService.class);

    private final AiTaskController controller = new AiTaskController(taskService);

    private static AiRunProgressDTO progress() {
        return new AiRunProgressDTO()
                .setRunId(41L)
                .setRunKey("run_0123456789abcdef01234567")
                .setStatus("FAILED")
                .setStepCount(1)
                .setLatestSeq(3)
                .setConversationId(31L)
                .setResultMessageId(77L)
                .setResultDigest("b".repeat(64))
                .setTaskStatus("FAILED")
                .setAttemptCount(3)
                .setLastErrorCode("502")
                .setRetryable(true);
    }

    @Test
    void everyEndpointDeclaresExactlyOneAuthenticationStrategy() {
        for (Method method : AiTaskController.class.getDeclaredMethods()) {
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
    void progressAndPageMapOnlyIdentifiersAndDigests() {
        when(taskService.progress(41L)).thenReturn(progress());
        AiTaskProgressRespVO respVO = controller.progress(41L).getData();
        assertThat(respVO.getRunKey()).isEqualTo("run_0123456789abcdef01234567");
        assertThat(respVO.getResultMessageId()).isEqualTo(77L);
        assertThat(respVO.getResultDigest()).hasSize(64);
        assertThat(respVO.isRetryable()).isTrue();
        assertThat(respVO.getTaskStatus()).isEqualTo("FAILED");

        when(taskService.pageProgress(any())).thenReturn(new PageResult<>(List.of(progress()), 1L));
        assertThat(controller.page(new AiTaskPageReqVO()).getData().getList()).hasSize(1);
        assertThat(AiTaskPageReqVO.class.getDeclaredFields())
                .as("归属只来自服务端身份：分页请求不允许携带应用/主体条件")
                .isEmpty();
    }

    @Test
    void responsesNeverCarryResultTextOrSecrets() {
        List<String> fields = Arrays.stream(AiTaskProgressRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fields)
                .as("进度响应不得回显结果正文、提示词或凭据")
                .doesNotContain("content", "output", "prompt", "credential", "token", "blockJson");
    }

    @Test
    void retryDelegatesWithOptimisticLockVersion() {
        controller.retry(new AiTaskRetryReqVO().setRunId(41L).setVersion(3));
        verify(taskService).retry(41L, 3);
    }
}
