package com.basicframework.module.ai.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.dto.AiConversationMessageSaveDTO;
import com.basicframework.module.ai.service.task.AiTaskService;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** O04 终态写入与工具接缝：两道栅栏、助手消息同事务落库、没有工具实现时明确不支持。 */
class AiRunTerminalWriterTest {

    private static final Long RUN_ID = 41L;

    private static final Long CONVERSATION_ID = 31L;

    private final AiRunMapper runMapper = mock(AiRunMapper.class);

    private final AiConversationService conversationService = mock(AiConversationService.class);

    private final AiTaskService taskService = mock(AiTaskService.class);

    private final AiRunTerminalWriter writer = new AiRunTerminalWriter(runMapper, conversationService, taskService);

    private static AiRunDO run() {
        return new AiRunDO()
                .setId(RUN_ID)
                .setConversationId(CONVERSATION_ID)
                .setStatus(AiRunDO.STATUS_ACCEPTED)
                .setStepCount(0)
                .setVersion(0);
    }

    private static AiTaskLeaseDTO lease() {
        return new AiTaskLeaseDTO()
                .setTaskId(61L)
                .setRunId(RUN_ID)
                .setOwner("worker-a")
                .setEpoch(1)
                .setAttempt(1);
    }

    @Test
    void writesTerminalStateAssistantMessageAndTaskOutcome() {
        when(runMapper.updateWithVersion(any(), anyInt())).thenReturn(1);

        assertThat(writer.finish(lease(), run(), AiRunDO.STATUS_SUCCEEDED, null, 1, "订单 A-1 已发货"))
                .isTrue();

        ArgumentCaptor<AiRunDO> runCaptor = ArgumentCaptor.forClass(AiRunDO.class);
        verify(runMapper).updateWithVersion(runCaptor.capture(), eq(0));
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(AiRunDO.STATUS_SUCCEEDED);
        assertThat(runCaptor.getValue().getStepCount()).isEqualTo(1);

        ArgumentCaptor<AiConversationMessageSaveDTO> messageCaptor =
                ArgumentCaptor.forClass(AiConversationMessageSaveDTO.class);
        verify(conversationService).appendMessage(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getRole()).isEqualTo(AiConversationMessageDO.ROLE_ASSISTANT);
        assertThat(messageCaptor.getValue().getSourceRunId()).isEqualTo(RUN_ID);

        verify(taskService).finish(lease(), AiRunDO.STATUS_SUCCEEDED, null);
    }

    @Test
    void failedTerminalStateWritesNoAssistantMessage() {
        when(runMapper.updateWithVersion(any(), anyInt())).thenReturn(1);

        assertThat(writer.finish(lease(), run(), AiRunDO.STATUS_FAILED, "502", 1, null))
                .isTrue();

        verify(conversationService, never()).appendMessage(any());
        verify(taskService).finish(lease(), AiRunDO.STATUS_FAILED, "502");
    }

    @Test
    void staleVersionOrLostLeaseDoesNotOverwriteAnything() {
        // 运行行乐观锁未命中：终态已被其它路径写入，不覆盖、不写消息、不动任务
        when(runMapper.updateWithVersion(any(), anyInt())).thenReturn(0);

        assertThat(writer.finish(lease(), run(), AiRunDO.STATUS_FAILED, "409", 1, "不该写入的输出"))
                .isFalse();
        verify(conversationService, never()).appendMessage(any());
        verify(taskService, never()).finish(any(), anyString(), any());

        // 无租约（同步调试路径）：只写运行终态
        when(runMapper.updateWithVersion(any(), anyInt())).thenReturn(1);
        assertThat(writer.finish(null, run(), AiRunDO.STATUS_SUCCEEDED, null, 1, null))
                .isTrue();
        verify(taskService, never()).finish(any(), anyString(), any());
    }

    @Test
    void noopToolExecutorReportsExplicitlyUnsupported() {
        AiNoopToolExecutor executor = new AiNoopToolExecutor();

        assertThat(executor.find("order.query")).as("没有受控工具实现时返回空，由调用方明确不支持").isEmpty();
        assertThat(executor.find(null)).isEmpty();
    }

    @Test
    void toolResultsCarryOnlyStableOutcome() {
        AiToolExecutor.ToolResult succeeded = AiToolExecutor.ToolResult.succeeded("OK");
        AiToolExecutor.ToolResult failed = AiToolExecutor.ToolResult.failed("TIMEOUT");

        assertThat(succeeded.succeeded()).isTrue();
        assertThat(succeeded.detailCode()).isEqualTo("OK");
        assertThat(failed.succeeded()).isFalse();
        assertThat(failed.detailCode()).isEqualTo("TIMEOUT");
        assertThat(failed.toString()).doesNotContain("sk-");
    }
}
