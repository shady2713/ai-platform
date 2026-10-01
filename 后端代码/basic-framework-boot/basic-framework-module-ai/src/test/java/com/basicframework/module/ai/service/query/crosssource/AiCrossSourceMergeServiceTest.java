package com.basicframework.module.ai.service.query.crosssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionDO;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionSourceDO;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceExecutionMapper;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceSourceContributionMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.authorization.crosssource.AiCrossSourceAuthorizationJudge;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFacts;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFactsResolver;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceCallerRole;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceIntegrity;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceResultContract;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Y07 跨源合并产出端（Y07）：读 Y04 台账、过 Y05 守卫、恒带口径。
 *
 * <p>本类用 Mockito 替掉两个 Mapper，理由是<b>台账内容</b>才是本卡的判定输入，
 * 台账本身的正确性由 Y04 的验收 IT 用真实 MySQL 证明；本类要钉的是
 * "拿到什么样的台账 → 产出什么样的响应"，以及每一条 fail-closed 出口。
 *
 * <p>授权事实同样打桩：本类验证的是"事实齐了之后怎么用"，
 * 真实授权事实的翻译由 {@code A03CrossSourceAccessFactsResolverTest} 覆盖。
 */
class AiCrossSourceMergeServiceTest {

    private static final String EXECUTION_KEY = "y07-exec-1";

    private static final Long APP_ID = 42L;

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 3, 1, 10, 0);

    private final AiCrossSourceExecutionMapper executionMapper = mock(AiCrossSourceExecutionMapper.class);

    private final AiCrossSourceSourceContributionMapper contributionMapper =
            mock(AiCrossSourceSourceContributionMapper.class);

    private final CrossSourceAccessFactsResolver factsResolver = mock(CrossSourceAccessFactsResolver.class);

    private final AiCrossSourceMergeService service = new AiCrossSourceMergeService(
            executionMapper, contributionMapper, new AiCrossSourceAuthorizationJudge(), factsResolver);

    // ---------- 台账夹具 ----------

    private static AiCrossSourceExecutionDO succeededExecution() {
        return new AiCrossSourceExecutionDO()
                .setId(7L)
                .setExecutionKey(EXECUTION_KEY)
                .setMetricCode("net_revenue")
                .setCurrency("CNY")
                .setStatus(AiCrossSourceExecutionDO.STATUS_SUCCEEDED)
                .setTotalAmount(new BigDecimal("130.00"))
                .setConsistencyAsOf(AS_OF)
                .setMaxSkewMillis(0L);
    }

    private static AiCrossSourceExecutionSourceDO countedRow(String role, String datasetCode, String amount) {
        return new AiCrossSourceExecutionSourceDO()
                .setExecutionId(7L)
                .setRole(role)
                .setDatasetCode(datasetCode)
                .setStatus(AiCrossSourceExecutionSourceDO.STATUS_COUNTED)
                .setAmount(new BigDecimal(amount));
    }

    private void givenLedger(AiCrossSourceExecutionDO execution, List<AiCrossSourceExecutionSourceDO> rows) {
        when(executionMapper.selectByExecutionKey(EXECUTION_KEY)).thenReturn(execution);
        when(contributionMapper.selectList(any())).thenReturn(rows);
    }

    /** 三个来源全部有权的真实事实（与 Y05 验收 IT 同口径：数据集 + 映射两条授权）。 */
    private void givenAllGranted() {
        Map<String, CrossSourceAccessFacts> facts = Map.of(
                "orders",
                granted("orders", "y04_orders"),
                "payment",
                granted("payment", "y04_payment"),
                "invoice",
                granted("invoice", "y04_invoice"));
        when(factsResolver.resolve(any())).thenReturn(facts);
    }

    private static CrossSourceAccessFacts granted(String role, String datasetCode) {
        return new CrossSourceAccessFacts(role, datasetCode, datasetCode, true, true, true);
    }

    private static CrossSourceMergeQuery query(CrossSourceCallerRole... roles) {
        return new CrossSourceMergeQuery(EXECUTION_KEY, APP_ID, "USER", "y07-alice", Set.of(roles), Set.of());
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("AT-071 正向：三个来源都真实有权时出合计、来源数与分来源明细（口径 COMPLETE）")
    void allAuthorizedSourcesProduceACompleteContract() {
        givenLedger(
                succeededExecution(),
                List.of(
                        countedRow("orders", "y04_orders", "100.00"),
                        countedRow("payment", "y04_payment", "30.00"),
                        countedRow("invoice", "y04_invoice", "0.00")));
        givenAllGranted();

        CrossSourceResultContract contract = service.merge(query(CrossSourceCallerRole.DATA_STEWARD));

        assertThat(contract.integrity().state()).isEqualTo(CrossSourceIntegrity.STATE_COMPLETE);
        assertThat(contract.rendersNothing()).isFalse();
        assertThat(contract.totalAmount()).isEqualByComparingTo("130.00");
        assertThat(contract.sourceCount()).isEqualTo(3);
        // 分来源明细只含角色与金额：不带数据集编号、来源系统与实体键
        assertThat(contract.sources()).hasSize(3);
        assertThat(contract.sources().get(0).role()).isEqualTo("orders");
        assertThat(contract.sources().get(0).amount()).isEqualByComparingTo("100.00");
        assertThat(contract.sources().get(0).toString())
                .doesNotContain("y04_orders")
                .doesNotContain("dataset");
    }

    @Test
    @DisplayName("AT-071 正向：角色不允许看分来源明细时口径为 PARTIAL，合计照出、明细不出")
    void roleWithoutBreakdownVisibilityGetsPartial() {
        givenLedger(
                succeededExecution(),
                List.of(
                        countedRow("orders", "y04_orders", "100.00"),
                        countedRow("payment", "y04_payment", "30.00"),
                        countedRow("invoice", "y04_invoice", "0.00")));
        givenAllGranted();

        // ANALYST 看不到 source_system：合计可出，分来源明细不可出
        CrossSourceResultContract contract = service.merge(query(CrossSourceCallerRole.ANALYST));

        assertThat(contract.integrity().state()).isEqualTo(CrossSourceIntegrity.STATE_PARTIAL);
        assertThat(contract.rendersNothing()).isFalse();
        assertThat(contract.totalAmount()).isEqualByComparingTo("130.00");
        assertThat(contract.sources()).isEmpty();
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：台账没有这次执行 → 404 稳定编号，不出任何响应体")
    void unknownExecutionKeyIsRefusedWithStableCode() {
        when(executionMapper.selectByExecutionKey(EXECUTION_KEY)).thenReturn(null);

        assertThatThrownBy(() -> service.merge(query(CrossSourceCallerRole.DATA_STEWARD)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_CONTRACT_EXECUTION_NOT_EXISTS.getCode());
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：结果不可出具时按 409 稳定编号拒绝，不与授权拒绝混为一谈")
    void nonIssuableExecutionIsRefusedWithTechnicalConflict() {
        AiCrossSourceExecutionDO failed = succeededExecution()
                .setStatus(AiCrossSourceExecutionDO.STATUS_FAILED)
                .setTotalAmount(null)
                .setConsistencyAsOf(null);
        givenLedger(failed, List.of(countedRow("orders", "y04_orders", "100.00")));

        // 受控结束是**技术**失败：处置动作是重跑，不是申请授权。
        // 报成 WITHHELD 会让 Y05 的提示把用户引向"联系管理员开通授权"，方向就错了。
        assertThatThrownBy(() -> service.merge(query(CrossSourceCallerRole.DATA_STEWARD)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_CONTRACT_RESULT_NOT_ISSUABLE_CONFLICT.getCode());
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：来源行缺数据集绑定 → 建立不了授权事实 → 口径缺失按 WITHHELD")
    void unresolvableBindingYieldsMissingIntegrityRatherThanANumber() {
        // 台账读到了、结果形状也完整，但某条来源行没有数据集绑定：
        // 既不能出数（可能被禁来源混在里面），也不该报"请去申请权限"（无从申请），
        // 只能按 Y07 的规定落 WITHHELD——这就是"缺口径绝不按 COMPLETE"的生产侧落点。
        givenLedger(
                succeededExecution(),
                List.of(
                        countedRow("orders", "y04_orders", "100.00"),
                        new AiCrossSourceExecutionSourceDO()
                                .setExecutionId(7L)
                                .setRole("payment")
                                .setDatasetCode("  ")
                                .setStatus(AiCrossSourceExecutionSourceDO.STATUS_COUNTED)
                                .setAmount(new BigDecimal("30.00"))));

        CrossSourceResultContract contract = service.merge(query(CrossSourceCallerRole.DATA_STEWARD));

        assertThat(contract.integrity().state()).isEqualTo(CrossSourceIntegrity.STATE_WITHHELD);
        assertThat(contract.integrity().reason()).isEqualTo(CrossSourceIntegrity.MISSING_REASON);
        assertThat(contract.rendersNothing()).isTrue();
        assertThat(contract.totalAmount()).isNull();
        assertThat(contract.sourceCount()).isNull();
    }

    @Test
    @DisplayName("AT-071 专项二（反向）：撤销一个来源授权后整份不出具，且错误码与无权一一对应")
    void revokedSourceRefusesTheWholeResult() {
        givenLedger(
                succeededExecution(),
                List.of(
                        countedRow("orders", "y04_orders", "100.00"),
                        countedRow("payment", "y04_payment", "30.00"),
                        countedRow("invoice", "y04_invoice", "0.00")));
        when(factsResolver.resolve(any()))
                .thenReturn(Map.of(
                        "orders",
                        granted("orders", "y04_orders"),
                        "payment",
                        granted("payment", "y04_payment"),
                        // payment 的授权已被撤销：数据可读但映射无权（Y05 专项二的专用编号）
                        "invoice",
                        new CrossSourceAccessFacts("invoice", "y04_invoice", "y04_invoice", true, true, false)));

        assertThatThrownBy(() -> service.merge(query(CrossSourceCallerRole.DATA_STEWARD)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED.getCode());
    }

    @Test
    @DisplayName("AT-071 专项二（反向）：差额可解——历史覆盖范围 ⊋ 本次覆盖范围时拒绝出具合计")
    void differentialIsNotRecoverableWhenHistoryCoversMore() {
        givenLedger(
                succeededExecution(),
                List.of(countedRow("orders", "y04_orders", "100.00"), countedRow("payment", "y04_payment", "30.00")));
        givenAllGranted();

        CrossSourceMergeQuery query = new CrossSourceMergeQuery(
                EXECUTION_KEY,
                APP_ID,
                "USER",
                "y07-alice",
                Set.of(CrossSourceCallerRole.DATA_STEWARD),
                // 调用方此前拿到过覆盖 orders+payment+invoice 的合计，
                // 而本次只覆盖 orders+payment：相减即解出 invoice
                Set.of("orders", "payment", "invoice"));

        assertThatThrownBy(() -> service.merge(query))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL.getCode());
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：主体上下文缺失按入参不合法拒绝（fail-closed，不按默认放行）")
    void missingSubjectContextIsRefusedRatherThanAssumedAllowed() {
        givenLedger(succeededExecution(), List.of(countedRow("orders", "y04_orders", "100.00")));
        givenAllGranted();

        List<CrossSourceMergeQuery> invalid = List.of(
                new CrossSourceMergeQuery(
                        "  ", APP_ID, "USER", "y07-alice", Set.of(CrossSourceCallerRole.ANALYST), Set.of()),
                new CrossSourceMergeQuery(
                        EXECUTION_KEY, null, "USER", "y07-alice", Set.of(CrossSourceCallerRole.ANALYST), Set.of()),
                new CrossSourceMergeQuery(
                        EXECUTION_KEY, APP_ID, " ", "y07-alice", Set.of(CrossSourceCallerRole.ANALYST), Set.of()),
                new CrossSourceMergeQuery(
                        EXECUTION_KEY, APP_ID, "USER", null, Set.of(CrossSourceCallerRole.ANALYST), Set.of()),
                // 角色集合为空：无法确定可见字段，按拒绝而不是退回最低角色
                new CrossSourceMergeQuery(EXECUTION_KEY, APP_ID, "USER", "y07-alice", Set.of(), Set.of()));

        for (CrossSourceMergeQuery query : invalid) {
            assertThatThrownBy(() -> service.merge(query))
                    .as("入参 %s 必须被拒绝", query)
                    .isInstanceOf(ServiceException.class)
                    .extracting(failure -> ((ServiceException) failure).getCode())
                    .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_CONTRACT_REQUEST_INVALID.getCode());
        }
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：null 查询按入参不合法拒绝，不抛 NullPointerException")
    void nullQueryIsRefusedWithStableCode() {
        assertThatThrownBy(() -> service.merge(null))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_CONTRACT_REQUEST_INVALID.getCode());
    }

    @Test
    @DisplayName("AT-071 专项二（反向）：台账里一个来源都没计入时不出数（缺失不是 0）")
    void ledgerWithNoCountedSourceIsRefusedRatherThanZeroFilled() {
        AiCrossSourceExecutionSourceDO missing = new AiCrossSourceExecutionSourceDO()
                .setExecutionId(7L)
                .setRole("orders")
                .setDatasetCode("y04_orders")
                .setStatus(AiCrossSourceExecutionSourceDO.STATUS_MISSING)
                .setAmount(new BigDecimal("100.00"));
        givenLedger(succeededExecution(), List.of(missing));
        givenAllGranted();

        // 缺失不是 0：不能拿 130.00 当合计出，也不能把 MISSING 行当已计入
        assertThatThrownBy(() -> service.merge(query(CrossSourceCallerRole.DATA_STEWARD)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_CONTRACT_RESULT_NOT_ISSUABLE_CONFLICT.getCode());
    }

    @Test
    @DisplayName("AT-071 专项二（反向）：无权但恰好取数失败的来源仍进判定范围（不能借失败绕过授权）")
    void unauthorizedButMissingSourceIsStillJudged() {
        // 反向用例：若实现只按 COUNTED 行判定，这个 MISSING 的无权来源就会被
        // 排除出判定范围，而它仍然是这次合并的一部分。
        AiCrossSourceExecutionSourceDO counted = countedRow("orders", "y04_orders", "100.00");
        AiCrossSourceExecutionSourceDO missing = new AiCrossSourceExecutionSourceDO()
                .setExecutionId(7L)
                .setRole("payment")
                .setDatasetCode("y04_payment")
                .setStatus(AiCrossSourceExecutionSourceDO.STATUS_MISSING);
        givenLedger(succeededExecution(), List.of(counted, missing));
        when(factsResolver.resolve(any()))
                .thenReturn(Map.of(
                        "orders",
                        granted("orders", "y04_orders"),
                        "payment",
                        new CrossSourceAccessFacts("payment", "y04_payment", "y04_payment", false, false, false)));

        assertThatThrownBy(() -> service.merge(query(CrossSourceCallerRole.DATA_STEWARD)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED.getCode());
    }

    @Test
    @DisplayName("装配类真的能产出一个可用的守卫：判定与两条披露闸门都跑得通")
    void configurationProducesAUsableJudge() {
        // 这不是"覆盖率测试"：它断言的是接线本身可用——
        // 判定器是 Y05 的纯类，Y07 用一个装配类把它变成可注入的 Bean，
        // 装配错了会在生产启动时才炸（表现为整个上下文起不来），而不是在判定时。
        AiCrossSourceAuthorizationJudge judge = new CrossSourceMergeConfiguration().crossSourceAuthorizationJudge();

        var grant = judge.judge(
                List.of("orders"),
                Map.of("orders", granted("orders", "y04_orders")),
                Set.of(CrossSourceCallerRole.DATA_STEWARD),
                Set.of());
        judge.requireDisclosableTotal(new BigDecimal("100.00"), List.of("orders"), Set.of(), Set.of());

        assertThat(grant.allowed()).isTrue();
        assertThat(grant.exposes("source_system")).isTrue();
        assertThat(judge.discloseSourceCount(List.of("orders"), Set.of())).isEqualTo(1);
    }
}
