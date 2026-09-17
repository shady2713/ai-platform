package com.basicframework.module.infra.archfixture;

import com.basicframework.module.ai.api.run.AiRunStatusEnum;

/**
 * 合规反例（负向对照）：module-infra 只消费 AI 薄契约模块发布的类型，
 * 不能产生规则 G 的违例；与 {@link InfraDependsOnAiInternalsFixture} 成对使用，
 * 证明豁免按"显式契约清单"生效，而不是按包名放行。
 */
public class InfraUsingPublishedAiContractFixture {

    public AiRunStatusEnum status() {
        return AiRunStatusEnum.QUEUED;
    }
}
