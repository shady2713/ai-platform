package com.basicframework.framework.datapermission.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.basicframework.framework.datapermission.core.annotation.DataPermission;
import com.basicframework.framework.datapermission.core.aop.DataPermissionAnnotationAdvisor;
import com.basicframework.framework.datapermission.core.db.DataPermissionRuleHandler;
import com.basicframework.framework.datapermission.core.rule.DataPermissionRule;
import com.basicframework.framework.datapermission.core.rule.DataPermissionRuleFactory;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class BasicFrameworkDataPermissionAutoConfigurationTest {

    private final BasicFrameworkDataPermissionAutoConfiguration configuration =
            new BasicFrameworkDataPermissionAutoConfiguration();

    @Test
    void createsFactoryAndPrependsSqlInterceptor() {
        DataPermissionRule rule = mock(DataPermissionRule.class);
        DataPermissionRuleFactory factory = configuration.dataPermissionRuleFactory(List.of(rule));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        DataPermissionRuleHandler handler = configuration.dataPermissionRuleHandler(interceptor, factory);

        assertThat(factory.getDataPermissionRules()).containsExactly(rule);
        assertThat(handler).isNotNull();
        assertThat(interceptor.getInterceptors()).first().isInstanceOf(DataPermissionInterceptor.class);
    }

    @Test
    void advisorMatchesClassAndMethodAnnotations() throws NoSuchMethodException {
        DataPermissionAnnotationAdvisor advisor = configuration.dataPermissionAnnotationAdvisor();
        Method annotatedMethod = MethodAnnotatedFixture.class.getDeclaredMethod("run");

        assertThat(advisor.getPointcut().getClassFilter().matches(ClassAnnotatedFixture.class))
                .isTrue();
        assertThat(advisor.getPointcut().getMethodMatcher().matches(annotatedMethod, MethodAnnotatedFixture.class))
                .isTrue();
        assertThat(advisor.getAdvice()).isNotNull();
    }

    @DataPermission
    private static class ClassAnnotatedFixture {}

    private static class MethodAnnotatedFixture {

        @DataPermission
        void run() {}
    }
}
