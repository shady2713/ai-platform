package com.basicframework.module.ai.service.query.crosssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionDO;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceExecutionMapper;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceSourceContributionMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.queryplan.CrossSourceQueryPlan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 跨源容量准入（Y04 第三步：超容量拒绝或转登记，不默认引入分布式查询集群）。
 *
 * <p>要钉住的是两条出口各自的行为：超容量且不允许登记时**拒绝**（不执行），
 * 允许登记时**落一条可查的 REGISTERED 记录**（不丢进内存队列）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiCrossSourceCapacityGateTest {

    private static final long ONE_MB = 1024L * 1024L;

    @Mock
    private AiCrossSourceExecutionMapper executionMapper;

    @Mock
    private AiCrossSourceSourceContributionMapper contributionMapper;

    private AiCrossSourceCapacityGate gate;

    private long generatedIds;

    @BeforeEach
    void setUp() {
        gate = new AiCrossSourceCapacityGate(executionMapper, contributionMapper);
        generatedIds = 500L;
        when(executionMapper.insert(any(AiCrossSourceExecutionDO.class))).thenAnswer(invocation -> {
            AiCrossSourceExecutionDO execution = invocation.getArgument(0);
            execution.setId(++generatedIds);
            return 1;
        });
    }

    @Test
    void admitsWhenSourcesFitWithinWarehouseCapacity() {
        assertThat(gate.admit(plan(3), budget(), 8, false, "exec-1")).isNull();
        verify(executionMapper, never()).insert(any(AiCrossSourceExecutionDO.class));
    }

    @Test
    void fallsBackToTheDefaultCapacityWhenTheGivenOneIsNotPositive() {
        // 上限缺省成 8 而不是 0：0 会让任何执行都被拒，配置错误不该表现为容量不足
        assertThat(gate.admit(plan(8), budget(), 0, false, "exec-1")).isNull();
        assertCode(
                () -> gate.admit(plan(9), budget(), -1, false, "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_CAPACITY_EXCEEDED);
    }

    @Test
    void refusesOverCapacityExecutionWhenRegistrationIsNotAllowed() {
        // 不"想办法跑完"：超容量的跨源聚合要跑完就得引入分布式查询集群，那是另一个数量级的承诺
        assertCode(
                () -> gate.admit(plan(9), budget(), 8, false, "exec-1"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_CAPACITY_EXCEEDED);
        verify(executionMapper, never()).insert(any(AiCrossSourceExecutionDO.class));
    }

    @Test
    void turnsOverCapacityExecutionIntoARegistrationThatIsQueryable() {
        AiCrossSourceExecutionDO registration = gate.admit(plan(9), budget(), 8, true, "exec-over");

        assertThat(registration).isNotNull();
        assertThat(registration.getStatus()).isEqualTo(AiCrossSourceExecutionDO.STATUS_REGISTERED);
        assertThat(registration.getExecutionKey()).isEqualTo("exec-over");
        // 预估扇出行数留痕：容量判据可复核
        assertThat(registration.getTotalRows()).isEqualTo(9);
        assertThat(registration.getConcurrentPeak()).isEqualTo(8);
        verify(executionMapper).insert(any(AiCrossSourceExecutionDO.class));
    }

    @Test
    void doesNotRegisterTheSameRequestTwice() {
        AiCrossSourceExecutionDO first = gate.admit(plan(9), budget(), 8, true, "exec-over");
        when(executionMapper.selectByExecutionKey("exec-over")).thenReturn(first);

        AiCrossSourceExecutionDO second = gate.admit(plan(9), budget(), 8, true, "exec-over");

        // 返回同一条而不是再登记一次：重复登记会把请求数放大
        assertThat(second.getId()).isEqualTo(first.getId());
        verify(executionMapper, times(1)).insert(any(AiCrossSourceExecutionDO.class));
    }

    @Test
    void refusesToOverwriteANonRegisteredExecutionWithARegistration() {
        when(executionMapper.selectByExecutionKey("exec-over"))
                .thenReturn(new AiCrossSourceExecutionDO()
                        .setId(9L)
                        .setExecutionKey("exec-over")
                        .setStatus(AiCrossSourceExecutionDO.STATUS_SUCCEEDED));

        assertCode(
                () -> gate.admit(plan(9), budget(), 8, true, "exec-over"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_CAPACITY_REGISTRATION_CONFLICT);
    }

    @Test
    void derivesARegistrationKeyFromThePlanWhenTheCallerGivesNone() {
        AiCrossSourceExecutionDO registration = gate.admit(plan(9), budget(), 8, true, "  ");

        assertThat(registration.getExecutionKey()).isEqualTo("crosssource-register-" + plan(9).planHash());
    }

    @Test
    void aRegistrationIsExecutableOnlyBeforeAnySourceIsCounted() {
        AiCrossSourceExecutionDO registered =
                new AiCrossSourceExecutionDO().setId(9L).setStatus(AiCrossSourceExecutionDO.STATUS_REGISTERED);
        when(contributionMapper.selectByRole(anyLong(), anyString())).thenReturn(null);
        assertThat(gate.executable(registered)).isTrue();

        when(contributionMapper.selectByRole(anyLong(), anyString()))
                .thenReturn(
                        new com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionSourceDO());
        // 已有来源行：登记态已转执行态，不再按"待执行登记"处理
        assertThat(gate.executable(registered)).isFalse();
        assertThat(gate.executable(null)).isFalse();
        assertCode(
                () -> gate.executable(
                        new AiCrossSourceExecutionDO().setId(9L).setStatus(AiCrossSourceExecutionDO.STATUS_FAILED)),
                AiErrorCodeConstants.AI_CROSS_SOURCE_CAPACITY_REGISTRATION_CONFLICT);
    }

    @Test
    void refusesMissingPlanOrBudget() {
        assertCode(
                () -> gate.admit(null, budget(), 8, false, "k"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
        assertCode(
                () -> gate.admit(plan(3), null, 8, false, "k"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
    }

    // ---- 夹具 ----

    private static CrossSourceBudget budget() {
        return new CrossSourceBudget(100, ONE_MB, 2, 5_000, 4 * ONE_MB, 300);
    }

    private static CrossSourceQueryPlan plan(int sourceCount) {
        List<CrossSourceQueryPlan.SourceSelection> sources = new ArrayList<>();
        List<String> order = new ArrayList<>();
        for (int index = 0; index < sourceCount; index++) {
            String role = "role_" + index;
            sources.add(new CrossSourceQueryPlan.SourceSelection("dset_" + role, 1, 1L, role, List.of("k"), true));
            order.add(role);
        }
        return new CrossSourceQueryPlan(
                "net_revenue",
                1,
                "caliber-hash",
                sources,
                order,
                "CNY",
                "Asia/Shanghai",
                "CALENDAR_MONTH",
                "CURRENCY",
                "plan-1");
    }

    private static void assertCode(
            Runnable operation, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(expected.getCode()));
    }
}
