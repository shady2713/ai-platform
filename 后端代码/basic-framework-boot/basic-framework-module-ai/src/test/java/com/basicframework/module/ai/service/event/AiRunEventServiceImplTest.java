package com.basicframework.module.ai.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.event.AiRunEventDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.mysql.event.AiRunEventMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunTaskMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.event.dto.AiRunEventDTO;
import com.basicframework.module.ai.service.event.dto.AiRunEventSnapshotDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** O05 运行事件：序号并发安全分配、状态与事件同事务、有界重放与窗口过期、显式取消。 */
class AiRunEventServiceImplTest {

    private static final Long RUN_ID = 41L;

    private static final Long APP_ID = 5L;

    private static final String EXTERNAL_USER = "u-1001";

    private final AiRunEventMapper eventMapper = mock(AiRunEventMapper.class);

    private final AiRunMapper runMapper = mock(AiRunMapper.class);

    private final AiRunTaskMapper taskMapper = mock(AiRunTaskMapper.class);

    private final AiConversationSubjectResolver subjectResolver = mock(AiConversationSubjectResolver.class);

    private final AiRunEventServiceImpl service =
            new AiRunEventServiceImpl(eventMapper, runMapper, taskMapper, subjectResolver);

    @BeforeEach
    void setUp() {
        when(subjectResolver.resolveCurrent())
                .thenReturn(Optional.of(new AiConversationSubject(APP_ID, AiSubjectType.USER, EXTERNAL_USER)));
        when(runMapper.selectOwned(RUN_ID, APP_ID, "USER", EXTERNAL_USER)).thenReturn(run());
        when(runMapper.selectById(RUN_ID)).thenReturn(run());
    }

    private static AiRunDO run() {
        return new AiRunDO()
                .setId(RUN_ID)
                .setRunKey("run_0123456789abcdef01234567")
                .setApplicationId(APP_ID)
                .setSubjectType("USER")
                .setExternalUserId(EXTERNAL_USER)
                .setStatus(AiRunDO.STATUS_RUNNING)
                .setEventSeq(3)
                .setVersion(2);
    }

    private static AiRunEventDO event(int seq) {
        return new AiRunEventDO()
                .setId(100L + seq)
                .setRunId(RUN_ID)
                .setSeq(seq)
                .setStatus(AiRunDO.STATUS_RUNNING)
                .setSchemaVersion(AiRunEventDO.SCHEMA_VERSION)
                .setVersion(0);
    }

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void appendAllocatesSeqOnTheRunRowAndCarriesRunKey() {
        when(eventMapper.allocateSeq(RUN_ID)).thenReturn(1);
        when(eventMapper.currentSeq(RUN_ID)).thenReturn(4);

        AiRunEventDTO appended = service.append(RUN_ID, AiRunDO.STATUS_RUNNING, "TEXT", "{\"text\":\"hi\"}");

        assertThat(appended.getSeq()).as("序号来自运行行的行锁内分配").isEqualTo(4);
        assertThat(appended.getSchemaVersion()).isEqualTo("1.0");
        assertThat(appended.getRunId()).as("事件对外用运行业务键").isEqualTo("run_0123456789abcdef01234567");
        ArgumentCaptor<AiRunEventDO> captor = ArgumentCaptor.forClass(AiRunEventDO.class);
        verify(eventMapper).insert(captor.capture());
        assertThat(captor.getValue().getRunId()).isEqualTo(RUN_ID);
        assertThat(captor.getValue().getSeq()).isEqualTo(4);
    }

    @Test
    void appendRejectsUnknownRunAndInvalidInput() {
        when(eventMapper.allocateSeq(RUN_ID)).thenReturn(0);
        assertThatThrownBy(() -> service.append(RUN_ID, AiRunDO.STATUS_RUNNING, null, null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));

        assertThatThrownBy(() -> service.append(null, AiRunDO.STATUS_RUNNING, null, null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> service.append(RUN_ID, " ", null, null))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        verify(eventMapper, never()).insert(any(AiRunEventDO.class));
    }

    @Test
    void replayIsBoundedAndAdvancesBySeq() {
        when(eventMapper.selectFirst(RUN_ID)).thenReturn(event(1));
        when(eventMapper.selectAfterSeq(RUN_ID, 1, 200)).thenReturn(List.of(event(2), event(3)));

        List<AiRunEventDTO> replayed = service.replay(RUN_ID, 1, 0);

        assertThat(replayed).extracting(AiRunEventDTO::getSeq).containsExactly(2, 3);
        assertThat(replayed).allSatisfy(item -> assertThat(item.getRunId()).isEqualTo("run_0123456789abcdef01234567"));

        // afterSeq 缺省按 0 处理（从头重放）
        when(eventMapper.selectAfterSeq(RUN_ID, 0, 5)).thenReturn(List.of(event(1)));
        assertThat(service.replay(RUN_ID, null, 5)).hasSize(1);
        assertThatThrownBy(() -> service.replay(RUN_ID, -1, 5))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
    }

    @Test
    void replayOutsideTheWindowFailsWithStableErrorAndSnapshotGuidesRecovery() {
        // 最早事件已经是 seq=5，而客户端还停在 2：窗口已过期
        when(eventMapper.selectFirst(RUN_ID)).thenReturn(event(5));

        assertThatThrownBy(() -> service.replay(RUN_ID, 2, 10))
                .as("窗口过期必须明确报错，而不是悄悄从当前位置开始")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_EVENT_WINDOW_EXPIRED));

        AiRunEventSnapshotDTO snapshot = service.snapshot(RUN_ID);
        assertThat(snapshot.getStatus()).isEqualTo(AiRunDO.STATUS_RUNNING);
        assertThat(snapshot.getLatestSeq()).isEqualTo(3);
        assertThat(snapshot.getEarliestSeq()).isEqualTo(5);
        assertThat(snapshot.getRunKey()).isEqualTo("run_0123456789abcdef01234567");
    }

    @Test
    void cancelWritesTerminalEventAndTerminatesTheTask() {
        AiRunTaskDO task = new AiRunTaskDO()
                .setId(61L)
                .setRunId(RUN_ID)
                .setTaskKind(AiRunTaskDO.KIND_RUN_STEP)
                .setStatus(AiRunTaskDO.STATUS_RUNNING)
                .setVersion(1);
        when(taskMapper.selectByRunAndKind(RUN_ID, AiRunTaskDO.KIND_RUN_STEP)).thenReturn(task);
        when(runMapper.updateWithVersion(any(), anyInt())).thenReturn(1);
        when(eventMapper.allocateSeq(RUN_ID)).thenReturn(1);
        when(eventMapper.currentSeq(RUN_ID)).thenReturn(4);

        service.cancel(RUN_ID, 2);

        ArgumentCaptor<AiRunDO> runCaptor = ArgumentCaptor.forClass(AiRunDO.class);
        verify(runMapper).updateWithVersion(runCaptor.capture(), eq(2));
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(AiRunDO.STATUS_CANCELLED);
        ArgumentCaptor<AiRunTaskDO> taskCaptor = ArgumentCaptor.forClass(AiRunTaskDO.class);
        verify(taskMapper).updateWithVersion(taskCaptor.capture(), eq(1));
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(AiRunTaskDO.STATUS_FAILED);
        assertThat(taskCaptor.getValue().getLastErrorCode()).isEqualTo("CANCELLED");
        ArgumentCaptor<AiRunEventDO> eventCaptor = ArgumentCaptor.forClass(AiRunEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(AiRunDO.STATUS_CANCELLED);
    }

    @Test
    void cancelIsRejectedForTerminalRunsAndForeignSubjects() {
        when(runMapper.selectOwned(RUN_ID, APP_ID, "USER", EXTERNAL_USER))
                .thenReturn(run().setStatus(AiRunDO.STATUS_SUCCEEDED));
        assertThatThrownBy(() -> service.cancel(RUN_ID, 2))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL));

        when(runMapper.selectOwned(RUN_ID, APP_ID, "USER", EXTERNAL_USER)).thenReturn(null);
        assertThatThrownBy(() -> service.cancel(RUN_ID, 2))
                .as("越权与不存在同语义")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        assertThatThrownBy(() -> service.snapshot(RUN_ID))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_NOT_FOUND));
        verify(runMapper, never()).updateWithVersion(any(), anyInt());
    }

    @Test
    void cancelLosesTheRaceToAnotherTerminalWrite() {
        when(runMapper.updateWithVersion(any(), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(RUN_ID, 2))
                .as("并发终态写入只有一个生效，另一个按终态冲突结束")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL));
        verify(eventMapper, never()).insert(any(AiRunEventDO.class));
    }
}
