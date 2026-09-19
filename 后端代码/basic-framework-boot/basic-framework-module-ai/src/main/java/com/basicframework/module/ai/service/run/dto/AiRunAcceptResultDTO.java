package com.basicframework.module.ai.service.run.dto;

import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行受理结果（O02）：只返回运行引用与固定版本，**不含**任何凭据、token 或请求正文。
 *
 * <p>{@link #reused} 为真表示本次受理命中幂等：返回首次受理产生的运行，平台不会重新发起模型调用。
 */
@Data
@Accessors(chain = true)
public class AiRunAcceptResultDTO {

    /** 运行编号 */
    private Long runId;

    /** 运行业务键 */
    private String runKey;

    /** 运行状态 */
    private String status;

    /** 固定的发布版本编号 */
    private Long releaseId;

    /** 固定的发布版本号 */
    private Integer releaseVersion;

    /** 是否命中幂等（复用首次受理的运行） */
    private boolean reused;

    /** 构造受理结果。 */
    public static AiRunAcceptResultDTO of(AiRunDO run, Integer releaseVersion, boolean reused) {
        return new AiRunAcceptResultDTO()
                .setRunId(run.getId())
                .setRunKey(run.getRunKey())
                .setStatus(run.getStatus())
                .setReleaseId(run.getReleaseId())
                .setReleaseVersion(releaseVersion)
                .setReused(reused);
    }
}
