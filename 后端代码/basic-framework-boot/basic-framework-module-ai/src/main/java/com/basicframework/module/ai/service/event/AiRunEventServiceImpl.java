package com.basicframework.module.ai.service.event;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_ALREADY_TERMINAL;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_EVENT_WINDOW_EXPIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_NOT_FOUND;

import com.basicframework.module.ai.dal.dataobject.event.AiRunEventDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.mysql.event.AiRunEventMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunTaskMapper;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.event.dto.AiRunEventDTO;
import com.basicframework.module.ai.service.event.dto.AiRunEventSnapshotDTO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运行事件实现（O05）。
 *
 * <p>序号在运行行上分配（`event_seq = event_seq + 1`），因此同一运行的事件序号并发安全；
 * 追加事件与运行状态写入由调用方放在同一个事务里（本类的 append 不自己开事务，
 * 以免把"状态 + 事件"拆成两次提交）。
 */
@Service
@RequiredArgsConstructor
public class AiRunEventServiceImpl implements AiRunEventService {

    /** 单次重放/订阅拉取的条数上限（有界订阅）。 */
    private static final int MAX_REPLAY_BATCH = 200;

    private final AiRunEventMapper eventMapper;

    private final AiRunMapper runMapper;

    private final AiRunTaskMapper taskMapper;

    private final AiConversationSubjectResolver subjectResolver;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiRunEventDTO append(Long runId, String status, String blockType, String blockJson) {
        if (runId == null || status == null || status.isBlank()) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (eventMapper.allocateSeq(runId) == 0) {
            throw exception(AI_RUN_NOT_FOUND);
        }
        int seq = eventMapper.currentSeq(runId);
        AiRunEventDO event = new AiRunEventDO()
                .setRunId(runId)
                .setSeq(seq)
                .setStatus(status)
                .setBlockType(blockType)
                .setBlockJson(blockJson)
                .setSchemaVersion(AiRunEventDO.SCHEMA_VERSION)
                .setVersion(0);
        eventMapper.insert(event);
        return toDTO(event, runKeyOf(runId));
    }

    @Override
    public List<AiRunEventDTO> replay(Long runId, Integer afterSeq, int limit) {
        AiRunDO run = requireOwnedRun(runId);
        int batch = limit < 1 ? MAX_REPLAY_BATCH : Math.min(limit, MAX_REPLAY_BATCH);
        int position = afterSeq == null ? 0 : afterSeq;
        if (position < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiRunEventDO earliest = eventMapper.selectFirst(runId);
        if (earliest != null && position > 0 && position < earliest.getSeq() - 1) {
            // 重放窗口已过期：明确报错并引导读取运行快照，而不是悄悄从当前位置开始
            throw exception(AI_RUN_EVENT_WINDOW_EXPIRED);
        }
        return eventMapper.selectAfterSeq(runId, position, batch).stream()
                .map(event -> toDTO(event, run.getRunKey()))
                .toList();
    }

    @Override
    public AiRunEventSnapshotDTO snapshot(Long runId) {
        AiRunDO run = requireOwnedRun(runId);
        AiRunEventDO earliest = eventMapper.selectFirst(runId);
        int latest = run.getEventSeq() == null ? 0 : run.getEventSeq();
        return new AiRunEventSnapshotDTO()
                .setRunId(run.getId())
                .setRunKey(run.getRunKey())
                .setStatus(run.getStatus())
                .setLatestSeq(latest)
                .setEarliestSeq(earliest == null ? 0 : earliest.getSeq());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long runId, Integer version) {
        AiRunDO run = requireOwnedRun(runId);
        if (isTerminal(run.getStatus())) {
            throw exception(AI_RUN_ALREADY_TERMINAL);
        }
        int current = run.getVersion() == null ? 0 : run.getVersion();
        if (runMapper.updateWithVersion(
                        new AiRunDO()
                                .setId(run.getId())
                                .setStatus(AiRunDO.STATUS_CANCELLED)
                                .setVersion(current + 1),
                        current)
                == 0) {
            throw exception(AI_RUN_ALREADY_TERMINAL);
        }
        // 取消是显式动作：写入终态事件并终止任务（任务租约由 worker 自行失效）
        AiRunTaskDO task = taskMapper.selectByRunAndKind(runId, AiRunTaskDO.KIND_RUN_STEP);
        if (task != null && !isTerminal(task.getStatus())) {
            taskMapper.updateWithVersion(
                    new AiRunTaskDO()
                            .setId(task.getId())
                            .setStatus(AiRunTaskDO.STATUS_FAILED)
                            .setLastErrorCode("CANCELLED")
                            .setVersion((task.getVersion() == null ? 0 : task.getVersion()) + 1),
                    task.getVersion() == null ? 0 : task.getVersion());
        }
        append(runId, AiRunDO.STATUS_CANCELLED, null, null);
    }

    private AiRunDO requireOwnedRun(Long runId) {
        AiConversationSubject subject = subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_RUN_NOT_FOUND));
        AiRunDO run = runId == null
                ? null
                : runMapper.selectOwned(
                        runId, subject.applicationId(), subject.subjectTypeName(), subject.externalUserId());
        if (run == null) {
            // 越权与不存在同语义
            throw exception(AI_RUN_NOT_FOUND);
        }
        return run;
    }

    private String runKeyOf(Long runId) {
        AiRunDO run = runMapper.selectById(runId);
        return run == null ? null : run.getRunKey();
    }

    private static boolean isTerminal(String status) {
        return AiRunDO.STATUS_SUCCEEDED.equals(status)
                || AiRunDO.STATUS_FAILED.equals(status)
                || AiRunDO.STATUS_CANCELLED.equals(status);
    }

    private static AiRunEventDTO toDTO(AiRunEventDO event, String runKey) {
        return new AiRunEventDTO()
                .setSchemaVersion(event.getSchemaVersion())
                .setSeq(event.getSeq())
                .setRunId(runKey)
                .setStatus(event.getStatus())
                .setBlockType(event.getBlockType())
                .setBlockJson(event.getBlockJson())
                .setCreatedAt(event.getCreateTime());
    }
}
