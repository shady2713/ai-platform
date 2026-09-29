package com.basicframework.module.ai.service.workflow.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 流程运行受理参数（服务层 DTO，X08）。
 *
 * <p>受理即固定版本：运行绑定受理时刻的最新**已发布**版本，之后编辑草稿或发布新版本
 * 都不影响本次运行。预算只能比平台默认更紧（上限见 {@code AiWorkflowBudget}）。
 */
@Data
@Accessors(chain = true)
public class AiWorkflowRunAcceptDTO {

    /** 流程编号（必填） */
    private Long workflowId;

    /** 受理幂等键（必填，同一流程内唯一；重复受理返回首次运行） */
    private String idempotencyKey;

    /** 数据等级（必填，L1_PUBLIC/L2_INTERNAL；模型节点外发等级） */
    private String dataLevel;

    /** 运行输入（开始节点的透传文本，缺省空串） */
    private String inputText;

    /** 步数预算（可选；缺省平台默认） */
    private Integer maxSteps;

    /** 耗时预算毫秒（可选；缺省平台默认） */
    private Long maxDurationMillis;
}
