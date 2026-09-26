package com.basicframework.module.ai.service.usage.dto;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 用量记录（服务层 DTO）：一次真实上游调用的计量事实（只含计量元数据）。 */
@Data
@Accessors(chain = true)
public class AiUsageRecordDTO {

    /** 调用标识（一次真实调用一个；重试再次请求模型时用新标识） */
    private String invocationId;

    /** 应用编号（按应用聚合的维度） */
    private Long applicationId;

    /** 上游耗时（毫秒，可空） */
    private Integer durationMs;

    /** 端点引用（编号/别名，不是地址） */
    private String endpointRef;

    /** 输入 token 数（未知留空，不写 0；只是计数，仍按敏感字段名规则排除 toString） */
    @ToString.Exclude
    private Long inputTokens;

    /** 模型标识（非秘密配置） */
    private String modelRef;

    /** 模型配置修订号 */
    private Integer modelRevision;

    /** 输出 token 数（未知留空，不写 0；只是计数，仍按敏感字段名规则排除 toString） */
    @ToString.Exclude
    private Long outputTokens;

    /** 运行编号（与 task 二选一） */
    private Long runId;

    /** 服务编号 */
    private Long serviceId;

    /** 调用结果（SUCCEEDED/FAILED/CANCELLED） */
    private String status;

    /** 主体标识（应用编号 + 主体摘要） */
    private String subjectRef;

    /** 任务编号（与 run 二选一） */
    private Long taskId;

    /** 计量来源（REPORTED/ESTIMATED/UNKNOWN） */
    private String usageSource;
}
