package com.basicframework.module.ai.service.query.planner;

import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanRequestDTO;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanResultDTO;

/**
 * 查询规划器（D05）：自然语言问题 → 已校验查询计划 或 澄清追问。
 *
 * <p>契约：本接口**不提供**"执行任意 SQL"的方法；调用方只能拿到
 * {@code ValidatedQueryPlan}（由校验器建立）或澄清结果。
 */
public interface AiQueryPlanner {

    /** 生成计划或澄清结果。 */
    AiQueryPlanResultDTO plan(AiQueryPlanRequestDTO request);

    /** 模型可见的数据集摘要（用于自检"模型到底看到了什么"）。 */
    String datasetSummary(Long datasetId, Long datasetVersionId, java.util.List<String> allowedFieldCodes);
}
