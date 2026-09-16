package com.basicframework.framework.datapermission.core.rule.dept;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.security.CurrentUserProvider;
import com.basicframework.module.system.api.permission.PermissionCommonApi;
import com.basicframework.module.system.api.permission.dto.DeptDataPermissionRespDTO;
import java.util.LinkedHashSet;
import java.util.Set;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeptDataPermissionRuleTest {

    private static final long USER_ID = 7L;

    @Mock
    private PermissionCommonApi permissionApi;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private DeptDataPermissionRule rule;

    @BeforeEach
    void setUp() {
        rule = new DeptDataPermissionRule(permissionApi, currentUserProvider);
        rule.addDeptColumn("orders", "dept_id");
        rule.addUserColumn("orders", "owner_id");
    }

    @Test
    void adminDeptAndSelfScope_buildsAliasedOrConditionAndCachesScope() {
        stubAdminIdentity();
        DeptDataPermissionRespDTO scope = scope(false, true, new LinkedHashSet<>(Set.of(10L, 20L)));
        when(permissionApi.getDeptDataPermission(USER_ID)).thenReturn(scope);

        Expression expression = rule.getExpression("orders", new Alias("o"));

        assertThat(expression.toString()).contains("o.dept_id IN", "10", "20", "o.owner_id = 7", " OR ");
        verify(currentUserProvider).setContext(DeptDataPermissionRule.CONTEXT_KEY, scope);
    }

    @Test
    void emptyScope_returnsAlwaysFalseCondition() {
        stubAdminIdentity();
        when(permissionApi.getDeptDataPermission(USER_ID)).thenReturn(scope(false, false, Set.of()));

        assertThat(rule.getExpression("orders", null).toString()).isEqualTo("null = null");
    }

    @Test
    void allScope_requiresNoAdditionalCondition() {
        stubAdminIdentity();
        when(permissionApi.getDeptDataPermission(USER_ID)).thenReturn(scope(true, false, Set.of()));

        assertThat(rule.getExpression("orders", null)).isNull();
    }

    @Test
    void anonymousOrNonAdminIdentity_addsNoAdminDepartmentCondition() {
        when(currentUserProvider.getLoginUserId()).thenReturn(null);
        assertThat(rule.getExpression("orders", null)).isNull();

        when(currentUserProvider.getLoginUserId()).thenReturn(USER_ID);
        when(currentUserProvider.getLoginUserType()).thenReturn(UserTypeEnum.MEMBER.getValue());
        assertThat(rule.getExpression("orders", null)).isNull();
        verifyNoInteractions(permissionApi);
    }

    @Test
    void cachedScope_avoidsRepeatedPermissionApiCall() {
        stubAdminIdentity();
        DeptDataPermissionRespDTO cached = scope(false, true, Set.of());
        when(currentUserProvider.getContext(DeptDataPermissionRule.CONTEXT_KEY, DeptDataPermissionRespDTO.class))
                .thenReturn(cached);

        assertThat(rule.getExpression("orders", null).toString()).isEqualTo("orders.owner_id = 7");
        verifyNoInteractions(permissionApi);
    }

    @Test
    void requestedSelfWithoutRegisteredUserColumn_failsClosed() {
        stubAdminIdentity();
        DeptDataPermissionRule deptOnlyRule = new DeptDataPermissionRule(permissionApi, currentUserProvider);
        deptOnlyRule.addDeptColumn("department_records", "dept_id");
        when(permissionApi.getDeptDataPermission(USER_ID)).thenReturn(scope(false, true, Set.of()));

        assertThat(deptOnlyRule.getExpression("department_records", null).toString())
                .isEqualTo("null = null");
    }

    @Test
    void malformedScope_failsClosedWithExplicitStateError() {
        stubAdminIdentity();
        DeptDataPermissionRespDTO malformed = scope(false, false, Set.of());
        malformed.setSelf(null);
        when(permissionApi.getDeptDataPermission(USER_ID)).thenReturn(malformed);

        assertThatThrownBy(() -> rule.getExpression("orders", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据权限响应不完整");
    }

    @Test
    void missingScopeWithoutAlias_failsClosedWithContextInsteadOfSecondaryNullPointer() {
        stubAdminIdentity();
        when(permissionApi.getDeptDataPermission(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> rule.getExpression("orders", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7", "orders");
    }

    @Test
    void registeredTableNames_cannotBeMutatedOutsideTheRule() {
        assertThatThrownBy(() -> rule.getTableNames().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(rule.getTableNames()).containsExactly("orders");
    }

    @Test
    void registration_rejectsInvalidSqlIdentifiers() {
        assertThatThrownBy(() -> rule.addDeptColumn("", "dept_id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表名");
        assertThatThrownBy(() -> rule.addDeptColumn("orders", "owner.id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("列名");
        assertThatThrownBy(() -> rule.addUserColumn("orders;drop", "owner_id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表名");
    }

    @Test
    void registration_isIdempotentButRejectsConflictingColumns() {
        rule.addDeptColumn("orders", "dept_id");
        rule.addUserColumn("orders", "owner_id");

        assertThatThrownBy(() -> rule.addDeptColumn("orders", "department_id"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders", "dept_id", "department_id");
        assertThatThrownBy(() -> rule.addUserColumn("orders", "creator_id"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders", "owner_id", "creator_id");
    }

    private static DeptDataPermissionRespDTO scope(Boolean all, Boolean self, Set<Long> deptIds) {
        return new DeptDataPermissionRespDTO().setAll(all).setSelf(self).setDeptIds(deptIds);
    }

    private void stubAdminIdentity() {
        when(currentUserProvider.getLoginUserId()).thenReturn(USER_ID);
        when(currentUserProvider.getLoginUserType()).thenReturn(UserTypeEnum.ADMIN.getValue());
    }
}
