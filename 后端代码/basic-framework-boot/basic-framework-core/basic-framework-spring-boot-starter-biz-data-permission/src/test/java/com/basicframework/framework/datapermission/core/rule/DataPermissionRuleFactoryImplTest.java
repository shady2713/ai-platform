package com.basicframework.framework.datapermission.core.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.datapermission.core.annotation.DataPermission;
import com.basicframework.framework.datapermission.core.aop.DataPermissionContextHolder;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DataPermissionRuleFactoryImplTest {

    private final FirstRule firstRule = new FirstRule();
    private final SecondRule secondRule = new SecondRule();
    private final DataPermissionRuleFactoryImpl factory =
            new DataPermissionRuleFactoryImpl(List.of(firstRule, secondRule));

    @AfterEach
    void clearContext() {
        DataPermissionContextHolder.clear();
    }

    @Test
    void noAnnotation_enablesEveryRegisteredRule() {
        assertThat(factory.getDataPermissionRule("Mapper.select")).containsExactly(firstRule, secondRule);
    }

    @Test
    void disabledAnnotation_returnsNoRules() {
        DataPermissionContextHolder.add(annotation("disabled"));

        assertThat(factory.getDataPermissionRule("Mapper.select")).isEmpty();
    }

    @Test
    void includeRules_takesPrecedenceOverExcludeRules() {
        DataPermissionContextHolder.add(annotation("includeFirstExcludeSecond"));

        assertThat(factory.getDataPermissionRule("Mapper.select")).containsExactly(firstRule);
    }

    @Test
    void excludeRules_removesOnlySelectedRule() {
        DataPermissionContextHolder.add(annotation("excludeFirst"));

        assertThat(factory.getDataPermissionRule("Mapper.select")).containsExactly(secondRule);
    }

    private static DataPermission annotation(String methodName) {
        try {
            Method method = Fixture.class.getDeclaredMethod(methodName);
            return method.getAnnotation(DataPermission.class);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    private static class Fixture {

        @DataPermission(enable = false)
        void disabled() {}

        @DataPermission(includeRules = FirstRule.class, excludeRules = SecondRule.class)
        void includeFirstExcludeSecond() {}

        @DataPermission(excludeRules = FirstRule.class)
        void excludeFirst() {}
    }

    private static class FirstRule implements DataPermissionRule {

        @Override
        public Set<String> getTableNames() {
            return Set.of();
        }

        @Override
        public Expression getExpression(String tableName, Alias tableAlias) {
            return null;
        }
    }

    private static final class SecondRule extends FirstRule {}
}
