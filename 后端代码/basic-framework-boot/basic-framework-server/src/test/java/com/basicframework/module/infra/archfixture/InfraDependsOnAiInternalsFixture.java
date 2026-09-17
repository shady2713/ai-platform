package com.basicframework.module.infra.archfixture;

import com.basicframework.module.ai.enums.AiTaskStatusEnum;

/**
 * 违例夹具：module-infra 引用 AI 模块内部实现（非薄契约），用于证明规则 G 能变红。
 */
public class InfraDependsOnAiInternalsFixture {

    private AiTaskStatusEnum taskStatus;
}
