package com.basicframework.framework.datapermission.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.datapermission.core.rule.DataPermissionRule;
import com.basicframework.framework.datapermission.core.rule.DataPermissionRuleFactory;
import java.util.List;
import java.util.Set;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataPermissionRuleHandlerTest {

    @Mock
    private DataPermissionRuleFactory ruleFactory;

    @Mock
    private DataPermissionRule firstRule;

    @Mock
    private DataPermissionRule secondRule;

    @Test
    void registeredRules_areCombinedWithAndAndReceiveTableAlias() {
        DataPermissionRuleHandler handler = new DataPermissionRuleHandler(ruleFactory);
        Table table = new Table("orders").withAlias(new Alias("o"));
        Expression deptCondition = new EqualsTo(new Column("o.dept_id"), new LongValue(10));
        Expression ownerCondition = new EqualsTo(new Column("o.owner_id"), new LongValue(7));
        when(ruleFactory.getDataPermissionRule("OrderMapper.selectPage")).thenReturn(List.of(firstRule, secondRule));
        when(firstRule.getTableNames()).thenReturn(Set.of("orders"));
        when(secondRule.getTableNames()).thenReturn(Set.of("orders"));
        when(firstRule.getExpression("orders", table.getAlias())).thenReturn(deptCondition);
        when(secondRule.getExpression("orders", table.getAlias())).thenReturn(ownerCondition);

        Expression result = handler.getSqlSegment(table, null, "OrderMapper.selectPage");

        assertThat(result).isInstanceOfSatisfying(AndExpression.class, combined -> {
            assertThat(combined.getLeftExpression()).isSameAs(deptCondition);
            assertThat(combined.getRightExpression()).isSameAs(ownerCondition);
        });
    }

    @Test
    void unregisteredTable_isNotModified() {
        DataPermissionRuleHandler handler = new DataPermissionRuleHandler(ruleFactory);
        when(ruleFactory.getDataPermissionRule("OrderMapper.selectPage")).thenReturn(List.of(firstRule));
        when(firstRule.getTableNames()).thenReturn(Set.of("orders"));

        assertThat(handler.getSqlSegment(new Table("global_config"), null, "OrderMapper.selectPage"))
                .isNull();
        verify(firstRule, never()).getExpression("global_config", null);
    }

    @Test
    void noActiveRules_isNotModified() {
        DataPermissionRuleHandler handler = new DataPermissionRuleHandler(ruleFactory);
        when(ruleFactory.getDataPermissionRule("OrderMapper.selectPage")).thenReturn(List.of());

        assertThat(handler.getSqlSegment(new Table("orders"), null, "OrderMapper.selectPage"))
                .isNull();
    }
}
