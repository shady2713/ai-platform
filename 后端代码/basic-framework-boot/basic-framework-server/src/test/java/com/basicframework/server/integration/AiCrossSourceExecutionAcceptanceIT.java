package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionDO;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionSourceDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMetricSemanticsRevisionDO;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceExecutionMapper;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceSourceContributionMapper;
import com.basicframework.module.ai.domain.semantic.AiMetricSemantics;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.query.crosssource.AiCrossSourceCapacityGate;
import com.basicframework.module.ai.service.query.crosssource.AiCrossSourceQueryExecutor;
import com.basicframework.module.ai.service.query.crosssource.CrossSourceBudget;
import com.basicframework.module.ai.service.query.crosssource.CrossSourceEntityKey;
import com.basicframework.module.ai.service.query.crosssource.CrossSourceExecutionResult;
import com.basicframework.module.ai.service.query.crosssource.CrossSourceSourceRequest;
import com.basicframework.module.ai.service.queryplan.AiCrossSourceQueryPlanValidator;
import com.basicframework.module.ai.service.queryplan.CrossSourceQueryPlan;
import com.basicframework.module.ai.service.semantic.AiMetricSemanticsService;
import com.basicframework.module.ai.service.semantic.dto.AiMetricSemanticsRevisionDraftDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMetricSemanticsSaveDTO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Y04 有界跨源查询执行与统一结果（真实 MySQL + Redis），核心是 **AT-070 / AT-071 加三条专项**。
 *
 * <p>取数链路完全走真实依赖：D04 数据集版本（建版本 → 验证 → 发布）→ D06 编译器（源内预聚合 SQL）
 * → D03 只读执行器（真实连接池 + 只读账号 + 真实 DECIMAL 精度）。跨源执行只做编排、预算与台账，
 * 没有任何 stub 冒充依赖：来源取数走的是与生产完全相同的
 * {@code MysqlCrossSourceSourceFetcher}。
 *
 * <p>验收逐条对应卡片 §4：
 * <ol>
 *   <li><b>AT-070 跨系统同名不同实体</b>：跨映射版本按裸键值关联被拒（409），
 *       钉在同一版本上才允许按编号关联；</li>
 *   <li><b>AT-071 跨系统部分源无权/故障</b>：必需来源真的取不到时整体受控结束且**不泄漏**
 *       其它来源的部分数字；可选来源取不到时结果显式标注缺哪个来源，绝不按 0 补齐；</li>
 *   <li><b>专项一 超过内存/行数预算受控结束</b>：真实 MySQL 返回超预算行集时抛稳定错误码，
 *       执行记录落 FAILED 且没有任何来源被部分写入——既不是 OOM，也不是静默截断出一个偏小的总数；</li>
 *   <li><b>专项二 源更新时显示数据时间差</b>：订单源 10:00、发票源 08:00，
 *       结果的 {@code consistencyAsOf} 取**最小值**、{@code maxSkewMillis} 如实暴露 2 小时偏移，
 *       超出容忍窗口则受控结束而不是假装同一时刻；</li>
 *   <li><b>专项三 各源独立重试不重复汇总</b>：同一执行键重试，合计仍是 125.00 而不是 250.00。
 *       机制是唯一键（每来源一行）+ CAS 覆盖（赋值不累加）+ 合计由已计入行求和，
 *       断言的是**数值**而不是"没抛异常"。</li>
 * </ol>
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiCrossSourceExecutionAcceptanceIT extends AbstractPersistenceIntegrationTest {

    private static final String METRIC_CODE = "net_revenue";

    private static final Long AUTHOR = 100L;

    private static final Long REVIEWER = 200L;

    private static final int TOLERATED_SKEW_SECONDS = 10_800;

    @Autowired
    private AiMetricSemanticsService semanticsService;

    @Autowired
    private AiCrossSourceExecutionMapper executionMapper;

    @Autowired
    private AiCrossSourceSourceContributionMapper contributionMapper;

    @Autowired
    private AiCrossSourceQueryExecutor executor;

    @Autowired
    private AiCrossSourceCapacityGate capacityGate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.basicframework.module.ai.service.connector.AiConnectorService connectorService;

    @Autowired
    private com.basicframework.module.ai.service.connector.AiMysqlConnectorService mysqlConnectorService;

    @Autowired
    private com.basicframework.module.ai.service.dataset.AiDatasetService datasetService;

    private final AiCrossSourceQueryPlanValidator planValidator = new AiCrossSourceQueryPlanValidator();

    private CrossSourceWarehouseFixture warehouse;

    @BeforeEach
    void prepare() {
        cleanUpExecutionData();
        loginAs(AUTHOR);
        warehouse =
                new CrossSourceWarehouseFixture(jdbcTemplate, connectorService, mysqlConnectorService, datasetService);
        warehouse.prepare(mysqlMappedPort());
    }

    @AfterEach
    void tearDown() {
        warehouse.cleanUp();
        SecurityContextHolder.clearContext();
    }

    // ================= 专项二：源更新时显示数据时间差 =================

    @Test
    void eachSourceReportsItsOwnTimePointAndTheResultIsOnlyValidUpToTheEarliestOne() {
        // 订单源更新到 10:00、发票源只到 08:00、回款 09:00 → 偏移必须被看见而不是抹平
        registerAndPublishCaliber();
        warehouse.seedData();

        CrossSourceExecutionResult result = runPlan("exec-skew", TOLERATED_SKEW_SECONDS);

        assertThat(result.totalAmount()).isEqualByComparingTo("130.00");
        // 一致性时间点取**各源最小值**：这是唯一"所有来源都成立"的时刻
        assertThat(result.consistencyAsOf()).isEqualTo(CrossSourceWarehouseFixture.INVOICE_TIME);
        assertThat(result.maxSkewMillis()).isEqualTo(2 * 60 * 60 * 1000L);
        // 每个来源自己的时间点都在结果里，不是一个笼统的"执行时刻"
        assertThat(result.sourceOf("order").asOf()).isEqualTo(CrossSourceWarehouseFixture.ORDER_TIME);
        assertThat(result.sourceOf("invoice").asOf()).isEqualTo(CrossSourceWarehouseFixture.INVOICE_TIME);
        assertThat(result.sourceOf("payment").asOf()).isEqualTo(CrossSourceWarehouseFixture.ORDER_TIME.minusHours(1));
        assertThat(result.complete()).isTrue();
        assertThat(result.usable()).isTrue();

        // 预算用量也是结果的一部分（可对账，不是只进日志）
        assertThat(result.budgetUsage().totalBytes()).isPositive();
        assertThat(result.budgetUsage().totalRows()).isEqualTo(5 + 2 + 1);
        // 取数是**有界并发**的：并发峰值不超过预算上限，且确实大于 1（真并行而不是串行）
        assertThat(result.budgetUsage().maxConcurrentUsed()).isGreaterThan(1).isLessThanOrEqualTo(2);

        // 执行记录里同样留痕：时间点与偏移可复核
        AiCrossSourceExecutionDO execution = executionMapper.selectByExecutionKey("exec-skew");
        assertThat(execution.getStatus()).isEqualTo(AiCrossSourceExecutionDO.STATUS_SUCCEEDED);
        assertThat(execution.getConsistencyAsOf()).isEqualTo(CrossSourceWarehouseFixture.INVOICE_TIME);
        assertThat(execution.getMaxSkewMillis()).isEqualTo(2 * 60 * 60 * 1000L);
        assertThat(execution.getTotalAmount()).isEqualByComparingTo("130.00");
        assertThat(execution.getMappingRevision()).isEqualTo(1L);
    }

    @Test
    void sourceTimePointsTooFarApartEndTheExecutionInsteadOfPretendingTheyAgree() {
        // 容忍 60 秒而实际偏移 2 小时：跨源合计失去意义 → 受控结束
        registerAndPublishCaliber();
        warehouse.seedData();

        assertCode(
                () -> runPlan("exec-skew-blocked", 60), AiErrorCodeConstants.AI_CROSS_SOURCE_CONSISTENCY_SKEW_CONFLICT);

        AiCrossSourceExecutionDO execution = executionMapper.selectByExecutionKey("exec-skew-blocked");
        assertThat(execution.getStatus()).isEqualTo(AiCrossSourceExecutionDO.STATUS_FAILED);
        assertThat(execution.getFailureCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_CONSISTENCY_SKEW_CONFLICT.getCode());
        // 偏大的合计数字**没有**被写进执行记录
        assertThat(execution.getTotalAmount()).isNull();
    }

    // ================= 专项一：超过内存/行数预算受控结束 =================

    @Test
    void exceedingTheRowBudgetEndsTheExecutionWithAStableCodeInsteadOfTruncatingSilently() {
        // 订单源真实返回 5 行，预算只给 2 行 → 受控结束
        registerAndPublishCaliber();
        warehouse.seedData();
        CrossSourceBudget tight =
                new CrossSourceBudget(2, 1024 * 1024, 2, 10_000, 4 * 1024 * 1024, TOLERATED_SKEW_SECONDS);

        assertCode(
                () -> executor.execute(validatedPlan(), sourceRequests(tight), tight, "exec-row-budget"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE);

        AiCrossSourceExecutionDO execution = executionMapper.selectByExecutionKey("exec-row-budget");
        assertThat(execution.getStatus()).isEqualTo(AiCrossSourceExecutionDO.STATUS_FAILED);
        assertThat(execution.getFailureCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE.getCode());
        // 关键：受控结束**绝不发布合计**——取数是有界并发的，别的来源可能已经计入，
        // 但合计只在全部来源收尾后才写，越界时它是 null，调用方拿不到偏小的数
        assertThat(execution.getTotalAmount()).isNull();
        // 越界的那个来源自己不会被计入（它在扣减预算时就终止了）
        assertThat(contributionMapper.selectCountedSources(execution.getId()))
                .extracting(AiCrossSourceExecutionSourceDO::getRole)
                .doesNotContain("order");
    }

    @Test
    void exceedingTheMemoryBudgetEndsTheExecutionRatherThanFillingTheHeap() {
        // 单源字节预算 1 字节，而真实中间结果远大于它
        registerAndPublishCaliber();
        warehouse.seedData();
        CrossSourceBudget tiny = new CrossSourceBudget(500, 1, 2, 10_000, 4 * 1024 * 1024, TOLERATED_SKEW_SECONDS);

        assertCode(
                () -> executor.execute(validatedPlan(), sourceRequests(tiny), tiny, "exec-memory-budget"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE);

        AiCrossSourceExecutionDO execution = executionMapper.selectByExecutionKey("exec-memory-budget");
        assertThat(execution.getStatus()).isEqualTo(AiCrossSourceExecutionDO.STATUS_FAILED);
        assertThat(contributionMapper.selectCountedSources(execution.getId())).isEmpty();
    }

    // ================= 专项三：各源独立重试不重复汇总 =================

    @Test
    void retryingAFailedExecutionDoesNotDoubleTheTotal() {
        // 专项三：第一次因可选来源（回款）取不到而受控结束，修好授权后重试。
        // 订单与发票在第一次结束时已各计入一行，重试必须**覆盖而不是叠加**：
        // 合计应从 125.00 变成 130.00（补上回款 5.00），而不是 255.00。
        registerAndPublishCaliber();
        warehouse.seedData();
        warehouse.revokeReadOn("payments");
        CrossSourceBudget budget = budget(TOLERATED_SKEW_SECONDS);
        List<CrossSourceSourceRequest> requests = List.of(
                sourceRequest("order", budget),
                sourceRequest("invoice", budget),
                optionalSourceRequest(
                        "payment", "y04_payments", CrossSourceWarehouseFixture.PAYMENT_CUSTOMERS, budget));

        CrossSourceExecutionResult first = executor.execute(validatedPlan(), requests, budget, "exec-retry");
        assertThat(first.missingRoles()).containsExactly("payment");
        assertThat(first.totalAmount()).isEqualByComparingTo("125.00");
        AiCrossSourceExecutionDO execution = executionMapper.selectByExecutionKey("exec-retry");
        Long executionId = execution.getId();
        assertThat(execution.getStatus()).isEqualTo(AiCrossSourceExecutionDO.STATUS_PARTIAL);
        // 第一次结束时订单与发票已计入（各一行）
        assertThat(contributionMapper.selectCountedSources(executionId))
                .extracting(AiCrossSourceExecutionSourceDO::getRole)
                .containsExactly("invoice", "order");
        assertThat(contributionMapper.selectByRole(executionId, "invoice").getAttemptCount())
                .isEqualTo(1);

        // 修好授权与数据后原地重试：PARTIAL 执行正是重试该处理的状态
        warehouse.grantReadOn("payments");
        warehouse.seedPayments();
        CrossSourceExecutionResult retried = executor.execute(validatedPlan(), requests, budget, "exec-retry");
        assertThat(retried.missingRoles()).isEmpty();
        assertThat(retried.totalAmount()).isEqualByComparingTo("130.00");

        // 机制证据一：台账里每个来源仍然只有一行（唯一键保证），金额没有被叠加
        List<AiCrossSourceExecutionSourceDO> counted = contributionMapper.selectCountedSources(executionId);
        assertThat(counted).hasSize(3);
        assertThat(counted)
                .extracting(AiCrossSourceExecutionSourceDO::getRole)
                .containsExactly("invoice", "order", "payment");
        assertThat(contributionOf(counted, "order")).isEqualByComparingTo("100.00");
        assertThat(contributionOf(counted, "invoice")).isEqualByComparingTo("25.00");
        assertThat(contributionOf(counted, "payment")).isEqualByComparingTo("5.00");

        // 机制证据二：合计由已计入行求和，与重试次数无关
        assertThat(counted.stream()
                        .map(row -> row.getAmount() == null ? BigDecimal.ZERO : row.getAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("130.00");
        assertThat(executionMapper.selectByExecutionKey("exec-retry").getTotalAmount())
                .isEqualByComparingTo("130.00");

        // 机制证据三：尝试次数可观测——重取过的来源 attemptCount 递增，但金额没翻倍
        assertThat(contributionMapper.selectByRole(executionId, "order").getAttemptCount())
                .isEqualTo(2);
        assertThat(contributionMapper.selectByRole(executionId, "invoice").getAttemptCount())
                .isEqualTo(2);
        // 已出完整结果的执行不再允许原地重跑：重算必须换执行键
        assertCode(
                () -> runPlan("exec-retry", TOLERATED_SKEW_SECONDS),
                AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT);
    }

    @Test
    void theSameExecutionKeyWithADifferentPlanIsRefused() {
        registerAndPublishCaliber();
        warehouse.seedData();
        runPlan("exec-key", TOLERATED_SKEW_SECONDS);

        // 同一键 + 不同计划 = 冲突：否则调用方无法分辨两个结果哪个是它要的
        CrossSourceQueryPlan original = validatedPlan();
        CrossSourceQueryPlan different = new CrossSourceQueryPlan(
                original.metricCode(),
                original.semanticsRevision(),
                original.semanticsDefinitionHash(),
                original.sources(),
                original.aggregationOrder(),
                original.currency(),
                original.timezone(),
                original.timeWindow(),
                original.unit(),
                "plan-other");
        CrossSourceBudget budget = budget(TOLERATED_SKEW_SECONDS);

        assertCode(
                () -> executor.execute(different, sourceRequests(budget), budget, "exec-key"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT);
    }

    // ================= AT-070：跨系统同名不同实体 =================

    @Test
    void sourceKeysAtDifferentMappingRevisionsAreNotAssociated() {
        // AT-070：同一个客户编号在两个映射版本下不是同一个统一对象
        registerAndPublishCaliber();
        warehouse.seedData();
        CrossSourceBudget budget = budget(TOLERATED_SKEW_SECONDS);
        CrossSourceSourceRequest invoiceAtRevision2 = new CrossSourceSourceRequest(
                "invoice",
                "y04_invoices",
                1,
                warehouse.connectorId(),
                warehouse.compiled("y04_invoices", CrossSourceWarehouseFixture.INVOICE_CUSTOMERS, budget),
                entityKeys(CrossSourceWarehouseFixture.INVOICE_CUSTOMERS, 2L),
                2L,
                "customer_name",
                "net_amount",
                new CrossSourceSourceRequest.LocalDateTimeColumn("source_as_of"),
                false);

        assertCode(
                () -> executor.execute(
                        validatedPlan(),
                        List.of(sourceRequest("order", budget), invoiceAtRevision2),
                        budget,
                        "exec-at070"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
    }

    @Test
    void joiningEntityKeysAtDifferentMappingRevisionsIsRefused() {
        // AT-070 的键语义：跨版本按裸键值关联会把两份事实并成一份
        CrossSourceEntityKey v1 = new CrossSourceEntityKey("C-001", 1L);
        CrossSourceEntityKey v2 = new CrossSourceEntityKey("C-001", 2L);

        assertThat(v1.sameMappingRevision(v2)).isFalse();
        assertCode(
                () -> CrossSourceEntityKey.join(v1, v2),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT);
        assertCode(
                () -> CrossSourceEntityKey.requireSingleMappingRevision(List.of(v1, v2)),
                AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT);
        // 钉在同一版本上才允许按编号关联
        assertThat(CrossSourceEntityKey.join(v1, new CrossSourceEntityKey("C-001", 1L)))
                .isEqualTo(v1);
    }

    // ================= AT-071：跨系统部分源无权/故障 =================

    @Test
    void aRequiredSourceWithoutAccessEndsTheExecutionWithoutLeakingOtherSourcesNumbers() {
        // AT-071：把订单源对象从只读授权里撤回 → 必需来源真的取不到
        registerAndPublishCaliber();
        warehouse.seedData();
        warehouse.revokeReadOn("orders");
        CrossSourceBudget budget = budget(TOLERATED_SKEW_SECONDS);

        // 上抛的是上游**可操作**的编号（连接器查询失败），跨源侧不包一层把它抹掉
        assertCode(
                () -> executor.execute(validatedPlan(), sourceRequests(budget), budget, "exec-at071"),
                AiErrorCodeConstants.AI_CONNECTOR_QUERY_FAILED);

        AiCrossSourceExecutionDO execution = executionMapper.selectByExecutionKey("exec-at071");
        assertThat(execution.getStatus()).isEqualTo(AiCrossSourceExecutionDO.STATUS_FAILED);
        // 不泄漏：取数并发进行，别的来源可能已计入，但**合计绝不发布**，
        // 调用方拿不到一个"少了必需来源却看起来正常"的数字
        assertThat(execution.getTotalAmount()).isNull();
        // 跨源侧的信息没丢：台账标出是哪个必需来源坏了
        assertThat(contributionMapper.selectByRole(execution.getId(), "order").getStatus())
                .isEqualTo(AiCrossSourceExecutionSourceDO.STATUS_FAILED);
    }

    @Test
    void anOptionalSourceWithoutAccessIsReportedAsMissingRatherThanZero() {
        // AT-071：回款是可选来源且无权访问 → 结果显式标注缺它，而不是按 0 补齐
        registerAndPublishCaliber();
        warehouse.seedData();
        warehouse.revokeReadOn("payments");
        CrossSourceBudget budget = budget(TOLERATED_SKEW_SECONDS);

        CrossSourceExecutionResult result = executor.execute(
                validatedPlan(),
                List.of(
                        sourceRequest("order", budget),
                        sourceRequest("invoice", budget),
                        optionalSourceRequest(
                                "payment", "y04_payments", CrossSourceWarehouseFixture.PAYMENT_CUSTOMERS, budget)),
                budget,
                "exec-at071-optional");

        assertThat(result.missingRoles()).containsExactly("payment");
        assertThat(result.complete()).isFalse();
        assertThat(result.usable()).isFalse();
        // 合计只有实际取到的两个来源：100.00 + 25.00；缺的 5.00 回款没有被当成 0 混进去，
        // 也没有把缺失来源的旧值算进来
        assertThat(result.totalAmount()).isEqualByComparingTo("125.00");

        AiCrossSourceExecutionDO execution = executionMapper.selectByExecutionKey("exec-at071-optional");
        assertThat(execution.getStatus()).isEqualTo(AiCrossSourceExecutionDO.STATUS_PARTIAL);
        assertThat(execution.getMissingRoles()).contains("payment");
        // 缺失来源留一行 MISSING：能区分"没取到"与"取到且为 0"
        assertThat(contributionMapper.selectByRole(execution.getId(), "payment").getStatus())
                .isEqualTo(AiCrossSourceExecutionSourceDO.STATUS_MISSING);
        assertThat(contributionMapper.selectCountedSources(execution.getId()))
                .extracting(AiCrossSourceExecutionSourceDO::getRole)
                .containsExactly("invoice", "order");
    }

    // ================= 容量：拒绝或转登记，不引入分布式查询集群 =================

    @Test
    void overCapacityExecutionIsRefusedOrRegisteredButNeverSilentlyRun() {
        registerAndPublishCaliber();
        CrossSourceBudget budget = budget(300);

        // 口径 3 个来源、数仓容量上限 2：超容量必须显式处理
        assertCode(
                () -> capacityGate.admit(validatedPlan(), budget, 2, false, "exec-capacity"),
                AiErrorCodeConstants.AI_CROSS_SOURCE_CAPACITY_EXCEEDED);
        // 拒绝时没有执行记录：容量不足不是"先跑起来再说"
        assertThat(executionMapper.selectByExecutionKey("exec-capacity")).isNull();

        // 允许登记时落一条可查的 REGISTERED 记录，而不是丢进内存队列
        AiCrossSourceExecutionDO registration = capacityGate.admit(validatedPlan(), budget, 2, true, "exec-registered");
        assertThat(registration.getStatus()).isEqualTo(AiCrossSourceExecutionDO.STATUS_REGISTERED);
        assertThat(executionMapper.selectByExecutionKey("exec-registered")).isNotNull();
        // 容量判据留痕：预估扇出行数可复核
        assertThat(registration.getTotalRows()).isEqualTo(3);
        // 重复登记返回同一条，不放大请求数
        assertThat(capacityGate
                        .admit(validatedPlan(), budget, 2, true, "exec-registered")
                        .getId())
                .isEqualTo(registration.getId());
        // 容量内的执行直接放行，不产生登记记录
        assertThat(capacityGate.admit(validatedPlan(), budget, 8, false, "exec-within"))
                .isNull();
        assertThat(executionMapper.selectByExecutionKey("exec-within")).isNull();
    }

    // ================= 夹具 =================

    /** 登记口径并以另一位审核人发布（复用 Y03 的发布链路，不重复实现）。 */
    private void registerAndPublishCaliber() {
        semanticsService.createSemantics(
                new AiMetricSemanticsSaveDTO().setMetricCode(METRIC_CODE).setMetricName("净收入"));
        AiMetricSemanticsRevisionDraftDTO draft = new AiMetricSemanticsRevisionDraftDTO();
        draft.setMetricCode(METRIC_CODE);
        draft.setMetricName("净收入");
        draft.setDefinitionJson(caliber());
        draft.setValidFrom("2026-01-01T00:00:00");
        semanticsService.createRevision(draft);
        loginAs(REVIEWER);
        AiMetricSemanticsRevisionDO published = semanticsService.publishRevision(
                semanticsService.getSemanticsByCode(METRIC_CODE).getId(), 1L, 0);
        assertThat(published.getStatus()).isEqualTo(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED);
        loginAs(AUTHOR);
    }

    /** 三来源全钉住、全部同币种同单位、全部按各自主键粒度预聚合。 */
    private static String caliber() {
        return "{\"metricCode\":\"" + METRIC_CODE + "\",\"unit\":\"CURRENCY\",\"currency\":\"CNY\","
                + "\"timezone\":\"Asia/Shanghai\",\"timeWindow\":\"CALENDAR_MONTH\",\"sources\":["
                + source("order", "y04_orders", "order_id", false) + ","
                + source("invoice", "y04_invoices", "invoice_id", false) + ","
                + source("payment", "y04_payments", "payment_id", true)
                + "],\"aggregationOrder\":[\"order\",\"invoice\",\"payment\"],\"conversion\":null}";
    }

    private static String source(String role, String datasetCode, String primaryKey, boolean optional) {
        return "{\"role\":\"" + role + "\",\"datasetCode\":\"" + datasetCode + "\",\"datasetVersion\":1,"
                + "\"mappingRevision\":1,\"unit\":\"CURRENCY\",\"currency\":\"CNY\",\"timezone\":\"Asia/Shanghai\","
                + "\"primaryKey\":[\"" + primaryKey + "\"],\"optional\":" + optional + "}";
    }

    private static String pinnedPlan() {
        return "{\"semanticsRevision\":1,\"sources\":["
                + "{\"role\":\"order\",\"datasetCode\":\"y04_orders\",\"datasetVersion\":1,\"mappingRevision\":1,"
                + "\"primaryKey\":[\"order_id\"],\"preAggregated\":true},"
                + "{\"role\":\"invoice\",\"datasetCode\":\"y04_invoices\",\"datasetVersion\":1,\"mappingRevision\":1,"
                + "\"primaryKey\":[\"invoice_id\"],\"preAggregated\":true},"
                + "{\"role\":\"payment\",\"datasetCode\":\"y04_payments\",\"datasetVersion\":1,\"mappingRevision\":1,"
                + "\"primaryKey\":[\"payment_id\"],\"preAggregated\":true}"
                + "],\"aggregationOrder\":[\"order\",\"invoice\",\"payment\"]}";
    }

    private CrossSourceQueryPlan validatedPlan() {
        AiMetricSemantics semantics =
                semanticsService.resolveVerified(METRIC_CODE, 1L, CrossSourceWarehouseFixture.AS_OF);
        return planValidator.validate(pinnedPlan(), semantics, Set.of());
    }

    private CrossSourceExecutionResult runPlan(String executionKey, int skewSeconds) {
        CrossSourceBudget budget = budget(skewSeconds);
        return executor.execute(validatedPlan(), sourceRequests(budget), budget, executionKey);
    }

    private static CrossSourceBudget budget(int skewSeconds) {
        return new CrossSourceBudget(500, 1024 * 1024, 2, 10_000, 4 * 1024 * 1024, skewSeconds);
    }

    /** 三个来源的取数请求：订单（5 客户）、发票（2 客户）、回款（1 客户）。 */
    private List<CrossSourceSourceRequest> sourceRequests(CrossSourceBudget budget) {
        return List.of(
                sourceRequest("order", budget),
                sourceRequest("invoice", budget),
                optionalSourceRequest(
                        "payment", "y04_payments", CrossSourceWarehouseFixture.PAYMENT_CUSTOMERS, budget));
    }

    private CrossSourceSourceRequest sourceRequest(String role, CrossSourceBudget budget) {
        return switch (role) {
            case "order" ->
                buildRequest(role, "y04_orders", CrossSourceWarehouseFixture.ORDER_CUSTOMERS, budget, false);
            case "invoice" ->
                buildRequest(role, "y04_invoices", CrossSourceWarehouseFixture.INVOICE_CUSTOMERS, budget, false);
            default -> buildRequest(role, "y04_payments", CrossSourceWarehouseFixture.PAYMENT_CUSTOMERS, budget, true);
        };
    }

    private CrossSourceSourceRequest optionalSourceRequest(
            String role, String datasetCode, List<String> customers, CrossSourceBudget budget) {
        return buildRequest(role, datasetCode, customers, budget, true);
    }

    private CrossSourceSourceRequest buildRequest(
            String role, String datasetCode, List<String> customers, CrossSourceBudget budget, boolean optional) {
        return new CrossSourceSourceRequest(
                role,
                datasetCode,
                1,
                warehouse.connectorId(),
                warehouse.compiled(datasetCode, customers, budget),
                entityKeys(customers, 1L),
                1L,
                "customer_name",
                "net_amount",
                new CrossSourceSourceRequest.LocalDateTimeColumn("source_as_of"),
                optional);
    }

    private static List<CrossSourceEntityKey> entityKeys(List<String> customers, long revision) {
        return customers.stream()
                .map(customer -> new CrossSourceEntityKey(customer, revision))
                .toList();
    }

    private static BigDecimal contributionOf(List<AiCrossSourceExecutionSourceDO> rows, String role) {
        return rows.stream()
                .filter(row -> role.equals(row.getRole()))
                .map(AiCrossSourceExecutionSourceDO::getAmount)
                .findFirst()
                .orElseThrow();
    }

    private static void loginAs(Long operatorId) {
        LoginUser loginUser = new LoginUser().setId(operatorId).setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private void cleanUpExecutionData() {
        jdbcTemplate.update("DELETE FROM ai_cross_source_execution_source");
        jdbcTemplate.update("DELETE FROM ai_cross_source_execution");
        jdbcTemplate.update("DELETE FROM ai_metric_semantics_revision");
        jdbcTemplate.update("DELETE FROM ai_metric_semantics WHERE metric_code = ?", METRIC_CODE);
        jdbcTemplate.update("DELETE FROM ai_dataset_version WHERE dataset_id IN"
                + " (SELECT id FROM ai_dataset WHERE code IN ('y04_orders','y04_invoices','y04_payments'))");
        jdbcTemplate.update("DELETE FROM ai_dataset WHERE code IN ('y04_orders','y04_invoices','y04_payments')");
        jdbcTemplate.update("DELETE FROM ai_connector WHERE code = ?", CrossSourceWarehouseFixture.CONNECTOR_CODE);
        jdbcTemplate.execute("DROP USER IF EXISTS '" + CrossSourceWarehouseFixture.READ_ONLY_USER + "'@'%'");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CrossSourceWarehouseFixture.CATALOG + ".orders");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CrossSourceWarehouseFixture.CATALOG + ".invoices");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CrossSourceWarehouseFixture.CATALOG + ".payments");
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + CrossSourceWarehouseFixture.CATALOG);
    }

    private static void assertCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(expected.getCode()));
    }
}
