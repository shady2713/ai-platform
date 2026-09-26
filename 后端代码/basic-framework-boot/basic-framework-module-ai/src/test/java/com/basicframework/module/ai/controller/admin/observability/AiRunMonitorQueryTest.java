package com.basicframework.module.ai.controller.admin.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunMonitorPageReqVO;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.mysql.conversation.AiConversationMessageMapper;
import com.basicframework.module.ai.dal.mysql.event.AiRunEventMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunTaskMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.usage.AiUsageLedgerService;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Q03 运维查询边界：筛选字面量受控、限制量有界、未知运行与越权同语义、事件正文不出库。 */
class AiRunMonitorQueryTest {

    private final AiRunMapper runMapper = mock(AiRunMapper.class);

    private final AiRunTaskMapper taskMapper = mock(AiRunTaskMapper.class);

    private final AiRunEventMapper eventMapper = mock(AiRunEventMapper.class);

    private final AiConversationMessageMapper messageMapper = mock(AiConversationMessageMapper.class);

    private final AiUsageLedgerService usageLedgerService = mock(AiUsageLedgerService.class);

    private final AiRunMonitorQuery query =
            new AiRunMonitorQuery(runMapper, taskMapper, eventMapper, messageMapper, usageLedgerService);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void rejectsUnknownFilterLiteralsAndInvertedWindowBeforeTouchingDatabase() {
        assertThatThrownBy(() -> query.pageRuns(null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> query.pageRuns(new AiRunMonitorPageReqVO().setStatus("DONE")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> query.pageRuns(new AiRunMonitorPageReqVO().setSubjectType("ROBOT")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() ->
                        query.pageRuns(new AiRunMonitorPageReqVO().setFrom(now).setTo(now)))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        verify(runMapper, never())
                .selectPage(
                        org.mockito.ArgumentMatchers.any(com.basicframework.framework.common.pojo.PageParam.class),
                        org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<AiRunDO>>any());
    }

    @Test
    void unknownRunIsRejectedInsteadOfLeakingExistence() {
        when(runMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> query.requireRun(9L))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        assertThatThrownBy(() -> query.requireRun(null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
    }

    @Test
    void timelineIsBoundedAndStartsFromTheGivenSequence() {
        when(eventMapper.selectAfterSeq(21L, 3, 200)).thenReturn(List.of());
        when(eventMapper.selectAfterSeq(21L, 0, 50)).thenReturn(List.of());

        query.timeline(21L, 3, 999);
        query.timeline(21L, null, 50);
        query.timeline(21L, -5, 50);

        verify(eventMapper).selectAfterSeq(21L, 3, 200);
        verify(eventMapper, org.mockito.Mockito.times(2)).selectAfterSeq(21L, 0, 50);
        verify(eventMapper, org.mockito.Mockito.atLeast(2)).selectAfterSeq(anyLong(), anyInt(), anyInt());
        assertThat(AiRunMonitorQuery.MAX_TIMELINE_LIMIT).isEqualTo(200);
    }

    @Test
    void emptyInputsShortCircuitWithRealEmptyValues() {
        assertThat(query.findStepTasks(List.of())).isEmpty();
        assertThat(query.usages(null)).isEmpty();
        verify(taskMapper, never()).selectList(org.mockito.ArgumentMatchers.any());
        verify(usageLedgerService, never()).listByRun(anyLong());
    }

    @Test
    void resultReferenceIsOnlyResolvedForSucceededRunsAndKeepsDigestOnly() {
        AiConversationMessageDO older = new AiConversationMessageDO()
                .setId(1L)
                .setConversationId(5L)
                .setContentHash("b".repeat(64))
                .setRole(AiConversationMessageDO.ROLE_ASSISTANT)
                .setSourceRunId(21L);
        AiConversationMessageDO latest = new AiConversationMessageDO()
                .setId(2L)
                .setConversationId(5L)
                .setContentHash("c".repeat(64))
                .setRole(AiConversationMessageDO.ROLE_ASSISTANT)
                .setSourceRunId(21L);
        when(messageMapper.selectAll(5L)).thenReturn(List.of(older, latest));

        AiConversationMessageDO resolved = query.resultMessage(
                new AiRunDO().setId(21L).setStatus(AiRunDO.STATUS_SUCCEEDED).setConversationId(5L));
        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).as("取最近一条助手消息").isEqualTo(2L);
        assertThat(resolved.getContentHash()).isEqualTo("c".repeat(64));

        assertThat(query.resultMessage(new AiRunDO()
                        .setId(21L)
                        .setStatus(AiRunDO.STATUS_FAILED)
                        .setConversationId(5L)))
                .as("未成功不给出结果引用")
                .isNull();
        assertThat(query.resultMessage(new AiRunDO()
                        .setId(21L)
                        .setStatus(AiRunDO.STATUS_SUCCEEDED)
                        .setConversationId(null)))
                .isNull();
        verify(messageMapper, org.mockito.Mockito.times(1)).selectAll(5L);
    }

    @Test
    void queryClassExposesNoWritableBusinessOperations() {
        for (java.lang.reflect.Method method : AiRunMonitorQuery.class.getDeclaredMethods()) {
            assertThat(method.getName())
                    .as("查询类只做只读取数：%s 不应出现写动词", method.getName())
                    .doesNotStartWith("save")
                    .doesNotStartWith("update")
                    .doesNotStartWith("delete")
                    .doesNotStartWith("insert");
        }
        assertThat(AiRunMonitorQuery.class.getDeclaredFields())
                .extracting(Field::getName)
                .doesNotContain("transactionTemplate");
    }
}
