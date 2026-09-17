package com.basicframework.module.ai.archfixture;

import com.basicframework.module.infra.dal.dataobject.job.JobDO;

/**
 * 违例夹具：AI 模块引用 module-infra 内部 DO（非薄契约），用于证明规则 F 能变红。
 */
public class AiDependsOnInfraInternalsFixture {

    private JobDO jobDO;
}
