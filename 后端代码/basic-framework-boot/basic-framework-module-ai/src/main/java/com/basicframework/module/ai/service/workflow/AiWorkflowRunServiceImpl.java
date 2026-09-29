package com.basicframework.module.ai.service.workflow;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_RUN_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WORKFLOW_VERSION_NOT_FOUND;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunNodeDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowVersionDO;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowMapper;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowRunMapper;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowRunNodeMapper;
import com.basicframework.module.ai.dal.mysql.workflow.AiWorkflowVersionMapper;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.domain.workflow.AiWorkflowBudget;
import com.basicframework.module.ai.domain.workflow.AiWorkflowGraph;
import com.basicframework.module.ai.enums.AiFieldRules;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunAcceptDTO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunResultDTO;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 流程受控运行实现（X08）。
 *
 * <p>版本固定点只有一个：受理时取流程的**最新已发布**版本并把它的编号写进运行行——
 * 执行器读的是那份不可变快照，草稿编辑、新版本发布、流程停用都改不了在途运行的语义。
 * 执行是同步有界的：预算在受理时快照（平台封顶内），网络调用不持有事务，终态用 CAS 写入。
 */
@Service
@RequiredArgsConstructor
public class AiWorkflowRunServiceImpl implements AiWorkflowRunService {

    /** 运行输入长度上限（与列宽一致）。 */
    private static final int MAX_INPUT_LENGTH = 4_000;

    private final AiWorkflowMapper workflowMapper;

    private final AiWorkflowVersionMapper versionMapper;

    private final AiWorkflowRunMapper runMapper;

    private final AiWorkflowRunNodeMapper nodeMapper;

    private final AiWorkflowRunExecutor executor;

    @Override
    public AiWorkflowRunResultDTO accept(AiWorkflowRunAcceptDTO acceptDTO) {
        AiWorkflowBudget budget = validate(acceptDTO);
        AiWorkflowDO workflow = workflowMapper.selectById(acceptDTO.getWorkflowId());
        if (workflow == null) {
            throw exception(AI_WORKFLOW_NOT_FOUND);
        }
        if (!AiWorkflowDO.STATUS_ENABLED.equals(workflow.getStatus())) {
            throw exception(AI_WORKFLOW_DISABLED);
        }
        String digest = AiWorkflowGraph.runDigest(
                acceptDTO.getWorkflowId(),
                acceptDTO.getDataLevel(),
                acceptDTO.getInputText() == null ? "" : acceptDTO.getInputText(),
                budget.getMaxSteps(),
                budget.getMaxDurationMillis());

        // 1) 先查幂等：同键同摘要复用首次运行，同键异摘要拒绝
        AiWorkflowRunResultDTO reused =
                reuseIfPresent(acceptDTO.getWorkflowId(), acceptDTO.getIdempotencyKey(), digest);
        if (reused != null) {
            return reused;
        }

        // 2) 固定版本：最新已发布版本（没有即拒绝——草稿不可运行）
        AiWorkflowVersionDO version = versionMapper
                .selectLatestPublished(acceptDTO.getWorkflowId())
                .orElseThrow(() -> exception(AI_WORKFLOW_VERSION_NOT_FOUND));

        // 3) 建运行行（唯一键兜底并发）；4) 事务之外执行（网络调用不持有事务）
        AiWorkflowRunDO run = new AiWorkflowRunDO()
                .setWorkflowId(acceptDTO.getWorkflowId())
                .setWorkflowVersionId(version.getId())
                .setIdempotencyKey(acceptDTO.getIdempotencyKey())
                .setRequestDigest(digest)
                .setStatus(AiWorkflowRunDO.STATUS_RUNNING)
                .setDataLevel(acceptDTO.getDataLevel())
                .setInputText(acceptDTO.getInputText() == null ? "" : acceptDTO.getInputText())
                .setNodeTotal(version.getNodeCount())
                .setNodeExecuted(0)
                .setMaxSteps(budget.getMaxSteps())
                .setMaxDurationMillis(budget.getMaxDurationMillis())
                .setStartedTime(LocalDateTime.now())
                .setVersion(0);
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException concurrentAccept) {
            AiWorkflowRunResultDTO winner =
                    reuseIfPresent(acceptDTO.getWorkflowId(), acceptDTO.getIdempotencyKey(), digest);
            if (winner == null) {
                throw exception(AI_IDEMPOTENCY_CONFLICT);
            }
            return winner;
        }
        executor.execute(run, version);
        return result(run, false);
    }

    @Override
    public AiWorkflowRunDO getRun(Long id) {
        AiWorkflowRunDO run = id == null ? null : runMapper.selectById(id);
        if (run == null) {
            throw exception(AI_WORKFLOW_RUN_NOT_FOUND);
        }
        return run;
    }

    @Override
    public PageResult<AiWorkflowRunDO> getRunPage(PageParam pageParam, Long workflowId, String status) {
        if (pageParam == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        return runMapper.selectPage(pageParam, workflowId, status);
    }

    @Override
    public List<AiWorkflowRunNodeDO> getRunNodes(Long runId) {
        getRun(runId);
        return nodeMapper.selectByRunId(runId);
    }

    /** 命中幂等时返回首次运行（含节点事实）；同键异摘要直接 409。 */
    private AiWorkflowRunResultDTO reuseIfPresent(Long workflowId, String idempotencyKey, String digest) {
        AiWorkflowRunDO existing = runMapper.selectByIdempotencyKey(workflowId, idempotencyKey);
        if (existing == null) {
            return null;
        }
        if (!digest.equals(existing.getRequestDigest())) {
            throw exception(AI_IDEMPOTENCY_CONFLICT);
        }
        return result(existing, true);
    }

    /** 运行行 + 节点事实组装（受理与幂等复用共用）。 */
    private AiWorkflowRunResultDTO result(AiWorkflowRunDO run, boolean reused) {
        AiWorkflowRunResultDTO result = new AiWorkflowRunResultDTO()
                .setRunId(run.getId())
                .setWorkflowId(run.getWorkflowId())
                .setWorkflowVersionId(run.getWorkflowVersionId())
                .setStatus(run.getStatus())
                .setOutputText(run.getOutputText())
                .setErrorCode(run.getErrorCode())
                .setNodeExecuted(run.getNodeExecuted())
                .setNodeTotal(run.getNodeTotal())
                .setDurationMs(run.getDurationMs())
                .setReused(reused)
                .setNodes(nodeMapper.selectByRunId(run.getId()).stream()
                        .map(node -> new AiWorkflowRunResultDTO.Node()
                                .setNodeKey(node.getNodeKey())
                                .setNodeType(node.getNodeType())
                                .setStatus(node.getStatus())
                                .setOutputText(node.getOutputText())
                                .setErrorCode(node.getErrorCode())
                                .setDurationMs(node.getDurationMs()))
                        .toList());
        AiWorkflowVersionDO version = versionMapper.selectById(run.getWorkflowVersionId());
        result.setVersionNo(version == null ? null : version.getVersionNo());
        return result;
    }

    /** 受理入参校验：等级/键/输入/预算（预算只能比平台默认更紧）。 */
    private static AiWorkflowBudget validate(AiWorkflowRunAcceptDTO acceptDTO) {
        if (acceptDTO == null
                || acceptDTO.getWorkflowId() == null
                || !AiFieldRules.isValidIdempotencyKey(acceptDTO.getIdempotencyKey())
                || !StringUtils.hasText(acceptDTO.getDataLevel())) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiOutboundLevel.parse(acceptDTO.getDataLevel()).orElseThrow(() -> exception(AI_REQUEST_INVALID));
        String input = acceptDTO.getInputText();
        if (input != null && input.length() > MAX_INPUT_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        return AiWorkflowBudget.of(acceptDTO.getMaxSteps(), acceptDTO.getMaxDurationMillis());
    }
}
