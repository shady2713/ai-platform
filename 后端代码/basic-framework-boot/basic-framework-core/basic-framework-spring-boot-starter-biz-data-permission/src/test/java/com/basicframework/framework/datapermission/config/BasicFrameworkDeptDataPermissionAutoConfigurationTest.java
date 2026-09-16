package com.basicframework.framework.datapermission.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.basicframework.framework.common.security.CurrentUserProvider;
import com.basicframework.framework.datapermission.core.rule.dept.DeptDataPermissionRule;
import com.basicframework.framework.datapermission.core.rule.dept.DeptDataPermissionRuleCustomizer;
import com.basicframework.module.system.api.permission.PermissionCommonApi;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BasicFrameworkDeptDataPermissionAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BasicFrameworkDeptDataPermissionAutoConfiguration.class))
            .withBean(PermissionCommonApi.class, () -> mock(PermissionCommonApi.class))
            .withBean(
                    DeptDataPermissionRuleCustomizer.class,
                    () -> rule -> rule.addDeptColumn("protected_records", "dept_id"));

    @Test
    void protectedTableRegistrationWithoutCurrentUserProvider_failsAtStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining(CurrentUserProvider.class.getName());
        });
    }

    @Test
    void protectedTableRegistrationWithCurrentUserProvider_createsRule() {
        contextRunner
                .withBean(CurrentUserProvider.class, () -> mock(CurrentUserProvider.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DeptDataPermissionRule.class);
                    assertThat(context.getBean(DeptDataPermissionRule.class).getTableNames())
                            .containsExactly("protected_records");
                });
    }
}
