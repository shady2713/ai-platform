package com.basicframework.module.ai.service.run.dto;

import com.basicframework.module.ai.domain.query.QueryScope;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行侧"执行固定计划"请求（R06）：刷新链路按当前权限重放**已固定**的查询版本。
 *
 * <p>与 {@link AiRunQueryExecutionRequestDTO} 的区别：这里不调用模型、不做规划——计划来自
 * 报表版本里保存的规范化计划（创建时已校验），刷新只负责"按当前权限再执行一次"。
 * 行范围仍必须由授权层给出（请求体给不出），没有行范围就拒绝执行。
 */
@Data
@Accessors(chain = true)
public class AiRunQueryExecutionFixedRequestDTO {

    /** 数据集编号（必填；由调用方按计划里的数据集标识解析） */
    private Long datasetId;

    /** 数据集版本编号（必填；计划锚定的那个版本） */
    private Long datasetVersionId;

    /** 规范化计划 JSON（必填；来自报表版本的查询引用） */
    private String planJson;

    /** 行范围（必填且必须有效；空集合即拒绝，绝不退回全库） */
    private QueryScope rowScope;

    /** 来源运行标识（可选，仅用于可追溯） */
    private String runKey;
}
