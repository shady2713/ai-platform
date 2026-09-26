package com.basicframework.module.ai.service.evaluation;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalResultDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalRunDO;
import java.util.List;

/**
 * 评测执行器与报告（Q04）。
 *
 * <p>执行口径（与真实运行同一套设施）：逐例用 {@code AiRunService} 受理一个真实运行
 * （同一服务、同一授权判定、同一模型端点与数据分级），再按任务租约执行，最后用
 * {@link AiEvalChecks} 对可观测事实做确定性核验——**模型评分不参与判定**。
 *
 * <p>计数口径：{@code passedCount} 只计 PASSED；FAILED 与 REVIEW_REQUIRED 计入
 * {@code failedCount}（后者待人工复核）；ERROR 计入 {@code errorCount}；三者之和等于 {@code caseTotal}。
 */
public interface AiEvalRunService {

    /** 开始一次评测（冻结套件快照 → 逐例执行 → 收敛运行行），返回评测运行编号。 */
    Long startRun(Long suiteId);

    /** 评测运行（不存在即拒绝）。 */
    AiEvalRunDO requireRun(Long runId);

    /** 评测运行分页（可按套件过滤）。 */
    PageResult<AiEvalRunDO> pageRuns(PageParam pageParam, Long suiteId);

    /** 运行内的逐例结果（按冻结顺序）。 */
    List<AiEvalResultDO> listResults(Long runId);

    /** 逐例结果分页（可按判定过滤）。 */
    PageResult<AiEvalResultDO> pageResults(PageParam pageParam, Long runId, String status);

    /** 可复现报告（运行快照 + 逐例判定与摘要；不含提示词与响应正文）。 */
    String report(Long runId);

    /** 人工复核（只对 REVIEW_PENDING 的结果有效；复核改变最终判定与结果摘要）。 */
    void review(Long resultId, boolean approve, String note);

    /** 结果（不存在即拒绝）。 */
    AiEvalResultDO requireResult(Long resultId);
}
