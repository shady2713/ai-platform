package com.basicframework.module.ai.controller.admin.observability;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_NOT_FOUND;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.controller.admin.observability.vo.AiRunMonitorPageReqVO;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.dal.dataobject.event.AiRunEventDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import com.basicframework.module.ai.dal.dataobject.usage.AiUsageLedgerDO;
import com.basicframework.module.ai.dal.mysql.conversation.AiConversationMessageMapper;
import com.basicframework.module.ai.dal.mysql.event.AiRunEventMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunTaskMapper;
import com.basicframework.module.ai.service.usage.AiUsageLedgerService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 运维运行查询（Q03）：管理端**只读**取数。
 *
 * <p>为什么在协议层包内：Q03 只授权 `controller/admin/observability`，不能新增 service
 * 子包；而管理端跨主体监控也无法复用按主体过滤的运行/任务服务（管理端没有应用主体会话，
 * 见 O01/O06 的主体解析）。因此这里只做"带条件的只读查询 + 归属校验"，不含任何业务写入；
 * 写入动作由 {@link AiRunRetryCommand} 承担。若后续卡放开 service 路径，应整体下沉。
 *
 * <p>不回传主体标识明文（只有 subjectType），不返回事件块正文。
 */
@Component
@RequiredArgsConstructor
public class AiRunMonitorQuery {

    /** 时间线单次上限（运维列表有界，避免大运行拖垮控制面）。 */
    public static final int MAX_TIMELINE_LIMIT = 200;

    private static final Set<String> RUN_STATUSES = Set.of(
            AiRunDO.STATUS_ACCEPTED,
            AiRunDO.STATUS_RUNNING,
            AiRunDO.STATUS_SUCCEEDED,
            AiRunDO.STATUS_FAILED,
            AiRunDO.STATUS_CANCELLED);

    private static final Set<String> SUBJECT_TYPES = Set.of("APP", "USER");

    private final AiRunMapper runMapper;

    private final AiRunTaskMapper taskMapper;

    private final AiRunEventMapper eventMapper;

    private final AiConversationMessageMapper messageMapper;

    private final AiUsageLedgerService usageLedgerService;

    /** 按应用/服务/状态/主体/时间窗筛选分页（服务端过滤，倒序）。 */
    public PageResult<AiRunDO> pageRuns(AiRunMonitorPageReqVO reqVO) {
        requireFilter(reqVO);
        return runMapper.selectPage(
                reqVO,
                new LambdaQueryWrapperX<AiRunDO>()
                        .eqIfPresent(AiRunDO::getApplicationId, reqVO.getApplicationId())
                        .eqIfPresent(AiRunDO::getServiceId, reqVO.getServiceId())
                        .eqIfPresent(AiRunDO::getStatus, reqVO.getStatus())
                        .eqIfPresent(AiRunDO::getSubjectType, reqVO.getSubjectType())
                        .geIfPresent(AiRunDO::getCreateTime, reqVO.getFrom())
                        .ltIfPresent(AiRunDO::getCreateTime, reqVO.getTo())
                        .orderByDesc(AiRunDO::getId));
    }

    /** 管理端读取运行：不存在即拒绝（越权与不存在同语义）。 */
    public AiRunDO requireRun(Long runId) {
        AiRunDO run = runId == null ? null : runMapper.selectById(runId);
        if (run == null) {
            throw exception(AI_RUN_NOT_FOUND);
        }
        return run;
    }

    /** 运行步骤任务（重试判据与状态展示都用它）。 */
    public AiRunTaskDO findStepTask(Long runId) {
        return runId == null ? null : taskMapper.selectByRunAndKind(runId, AiRunTaskDO.KIND_RUN_STEP);
    }

    /** 批量取步骤任务（列表页一次查询，避免逐行取任务的 N+1）。 */
    public Map<Long, AiRunTaskDO> findStepTasks(List<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Map.of();
        }
        return taskMapper
                .selectList(new LambdaQueryWrapperX<AiRunTaskDO>()
                        .in(AiRunTaskDO::getRunId, runIds)
                        .eq(AiRunTaskDO::getTaskKind, AiRunTaskDO.KIND_RUN_STEP))
                .stream()
                .collect(Collectors.toMap(AiRunTaskDO::getRunId, task -> task, (first, second) -> first));
    }

    /** 事件时间线（按序号升序，有界）。 */
    public List<AiRunEventDO> timeline(Long runId, Integer afterSeq, Integer limit) {
        int bounded = limit == null || limit < 1 ? MAX_TIMELINE_LIMIT : Math.min(limit, MAX_TIMELINE_LIMIT);
        int position = afterSeq == null || afterSeq < 0 ? 0 : afterSeq;
        return eventMapper.selectAfterSeq(runId, position, bounded);
    }

    /** 该运行的模型计量（真实事实，来源未知的也在列）。 */
    public List<AiUsageLedgerDO> usages(Long runId) {
        return runId == null ? List.of() : usageLedgerService.listByRun(runId);
    }

    /** 结果引用：成功运行的助手消息（只取摘要，不复制正文）。 */
    public AiConversationMessageDO resultMessage(AiRunDO run) {
        if (run == null || run.getConversationId() == null || !AiRunDO.STATUS_SUCCEEDED.equals(run.getStatus())) {
            return null;
        }
        return messageMapper.selectAll(run.getConversationId()).stream()
                .filter(message -> AiConversationMessageDO.ROLE_ASSISTANT.equals(message.getRole()))
                .filter(message -> run.getId().equals(message.getSourceRunId()))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    /** 筛选条件必须是受控词表与合法时间窗，避免"看起来筛了其实没筛"。 */
    private void requireFilter(AiRunMonitorPageReqVO reqVO) {
        if (reqVO == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (reqVO.getStatus() != null && !RUN_STATUSES.contains(reqVO.getStatus())) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (reqVO.getSubjectType() != null && !SUBJECT_TYPES.contains(reqVO.getSubjectType())) {
            throw exception(AI_REQUEST_INVALID);
        }
        LocalDateTime from = reqVO.getFrom();
        LocalDateTime to = reqVO.getTo();
        if (from != null && to != null && !from.isBefore(to)) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
