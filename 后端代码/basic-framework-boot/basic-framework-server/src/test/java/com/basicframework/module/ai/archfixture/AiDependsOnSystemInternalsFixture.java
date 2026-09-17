package com.basicframework.module.ai.archfixture;

import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;

/**
 * 违例夹具：AI 模块引用 module-system 内部 DO（非薄契约）。
 *
 * <p>只被 {@code ModuleBoundaryArchitectureRejectionTest} 使用，用于证明规则 E 能变红；
 * 生产门禁以 DoNotIncludeTests 排除本包，不会把夹具自身当成真实依赖。
 */
public class AiDependsOnSystemInternalsFixture {

    private AdminUserDO adminUserDO;
}
