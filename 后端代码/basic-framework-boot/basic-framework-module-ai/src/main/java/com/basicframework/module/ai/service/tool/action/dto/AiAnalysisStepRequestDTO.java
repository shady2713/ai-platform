package com.basicframework.module.ai.service.tool.action.dto;

import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;

/** 分析步骤请求（D09）：一次工具调用步骤。 */
@Data
@Accessors(chain = true)
public class AiAnalysisStepRequestDTO {

    /** 运行编号（必填） */
    private Long runId;

    /** 所属应用编号（必填；确认必须由同一主体完成） */
    private Long applicationId;

    /** 主体类型（APP/USER，必填） */
    private String subjectType;

    /** 外部用户标识（APP 主体为空串） */
    private String externalUserId;

    /** 工具标识（必填） */
    private String toolCode;

    /** 工具参数（按工具版本的输入 schema 校验） */
    private Map<String, Object> arguments;

    /** 运行预算（缺省用平台默认：步数/耗时/工具次数） */
    private com.basicframework.module.ai.domain.runtime.AiRunBudget budget;
}
