package com.basicframework.module.ai.service.usage;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 一次上游调用的自有计量记录（M05）。
 *
 * <p>只记录计量需要的字段：调用标识、端点与版本身份、能力、结果状态、耗时与用量，
 * **不记录提示词、响应正文、凭据或上游报文**。用量语义沿用接缝词表：
 * {@code promptTokens}/{@code completionTokens} 为空表示 UNKNOWN（上游未提供），
 * {@code estimated=true} 表示平台估算值——两者都不得当成真实计量参与结算。
 *
 * <p>记录由 Q02 持久化；本卡只产生记录并通过 {@link AiModelUsageRecorder} 交给上层，
 * 不提前创建尚未交付的账本表。
 */
@Data
@Accessors(chain = true)
@ToString
public class AiModelInvocationRecord {

    /** 每次实际调用分配一次的调用标识（可作为幂等键与账本主键的一部分） */
    private String invocationId;

    /** 端点编号 */
    private Long endpointId;

    /** 调用时的配置版本 */
    private Integer configRevision;

    /** 调用时的凭据版本（只记版本号，不含凭据） */
    @ToString.Exclude
    private Integer credentialRevision;

    /** 能力标识（ModelCapability 名称） */
    private String capability;

    /** 结果状态：SUCCEEDED / FAILED */
    private String status;

    /** 真实调用耗时（毫秒） */
    private long latencyMs;

    /** 上游给出的输入 token 数；为空表示 UNKNOWN。计数不是凭据，但按敏感字段门禁排除出 toString。 */
    @ToString.Exclude
    private Integer promptTokens;

    /** 上游给出的输出 token 数；为空表示 UNKNOWN；同上，计数不是凭据。 */
    @ToString.Exclude
    private Integer completionTokens;

    /** 是否为平台估算值（true 时不得参与结算） */
    private boolean estimated;

    /** 失败原因（ModelException.Reason 名称）；成功时为空 */
    private String errorReason;

    /** 是否成功。 */
    public boolean succeeded() {
        return "SUCCEEDED".equals(status);
    }

    /** 用量是否可用（至少一个计数非空）。 */
    public boolean usageKnown() {
        return promptTokens != null || completionTokens != null;
    }
}
