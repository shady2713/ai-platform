package com.basicframework.module.system.framework.datapermission.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.basicframework.framework.datapermission.core.rule.dept.DeptDataPermissionRule;
import org.junit.jupiter.api.Test;

/**
 * {@link DataPermissionConfiguration} 单元测试
 *
 */
class DataPermissionConfigurationTest {

    private final DataPermissionConfiguration configuration = new DataPermissionConfiguration();

    @Test
    void sysDeptDataPermissionRuleCustomizer_registersColumnRules() {
        DeptDataPermissionRule rule = mock(DeptDataPermissionRule.class);

        configuration.sysDeptDataPermissionRuleCustomizer().customize(rule);

        verify(rule).addDeptColumn("system_users", "dept_id");
        verify(rule).addDeptColumn("system_dept", "id");
        verify(rule).addUserColumn("system_users", "id");
    }
}
