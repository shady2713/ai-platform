package com.basicframework.framework.datapermission.config;

import com.basicframework.framework.common.security.CurrentUserProvider;
import com.basicframework.framework.datapermission.core.rule.dept.DeptDataPermissionRule;
import com.basicframework.framework.datapermission.core.rule.dept.DeptDataPermissionRuleCustomizer;
import com.basicframework.module.system.api.permission.PermissionCommonApi;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * 基于部门的数据权限 AutoConfiguration
 *
 * <p>只要存在受保护表的 {@link DeptDataPermissionRuleCustomizer}，就必须同时存在 {@link CurrentUserProvider}。
 * 该实现由 security starter 的自动配置先行注册；缺失时启动失败，禁止静默移除行级权限规则。
 *
 */
@AutoConfiguration
@ConditionalOnBean(DeptDataPermissionRuleCustomizer.class)
public class BasicFrameworkDeptDataPermissionAutoConfiguration {

    @Bean
    public DeptDataPermissionRule deptDataPermissionRule(
            PermissionCommonApi permissionApi,
            CurrentUserProvider currentUserProvider,
            List<DeptDataPermissionRuleCustomizer> customizers) {
        // 创建 DeptDataPermissionRule 对象
        DeptDataPermissionRule rule = new DeptDataPermissionRule(permissionApi, currentUserProvider);
        // 补全表配置
        customizers.forEach(customizer -> customizer.customize(rule));
        return rule;
    }
}
