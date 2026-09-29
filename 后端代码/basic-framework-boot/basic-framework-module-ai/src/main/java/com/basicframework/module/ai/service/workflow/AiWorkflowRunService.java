package com.basicframework.module.ai.service.workflow;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunDO;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowRunNodeDO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunAcceptDTO;
import com.basicframework.module.ai.service.workflow.dto.AiWorkflowRunResultDTO;
import java.util.List;

/**
 * 流程受控运行（X08）：受理即固定版本的同步有界执行。
 *
 * <p>受理顺序固定：校验输入 → 流程必须启用 → 幂等复用判定 → 固定最新**已发布**版本 →
 * 建运行行 → 执行器走图（网络调用在事务之外）→ 终态 CAS。并发由
 * （流程, 幂等键）唯一键兜底：冲突后在事务之外回读赢家并比较摘要，相同返回原运行、不同返回 409。
 */
public interface AiWorkflowRunService {

    /** 受理并同步执行一次流程运行（幂等；返回终态结果与逐节点事实）。 */
    AiWorkflowRunResultDTO accept(AiWorkflowRunAcceptDTO acceptDTO);

    /** 按编号取运行。 */
    AiWorkflowRunDO getRun(Long id);

    /** 运行分页（按流程过滤，状态精确）。 */
    PageResult<AiWorkflowRunDO> getRunPage(PageParam pageParam, Long workflowId, String status);

    /** 运行的节点留痕（按执行顺序）。 */
    List<AiWorkflowRunNodeDO> getRunNodes(Long runId);
}
