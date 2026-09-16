package com.basicframework.module.system.framework.datapermission.config;

import com.basicframework.framework.datapermission.core.rule.dept.DeptDataPermissionRuleCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * system 模块的数据权限 Configuration
 *
 */
@Configuration(proxyBeanMethods = false)
public class DataPermissionConfiguration {

    @Bean
    public DeptDataPermissionRuleCustomizer sysDeptDataPermissionRuleCustomizer() {
        return rule -> {
            // 使用显式表名与列名，供数据权限契约门禁核对运行时规则和最终 schema。
            rule.addDeptColumn("system_users", "dept_id");
            rule.addDeptColumn("system_dept", "id");
            rule.addUserColumn("system_users", "id");
        };
    }
}
