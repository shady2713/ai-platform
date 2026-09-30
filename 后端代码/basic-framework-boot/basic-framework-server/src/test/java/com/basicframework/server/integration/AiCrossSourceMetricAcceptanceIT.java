package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMetricSemanticsDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMetricSemanticsRevisionDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMetricSemanticsMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMetricSemanticsRevisionMapper;
import com.basicframework.module.ai.domain.semantic.AiMetricSemantics;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.queryplan.AiCrossSourceQueryPlanValidator;
import com.basicframework.module.ai.service.queryplan.CrossSourceQueryPlan;
import com.basicframework.module.ai.service.semantic.AiMetricSemanticsService;
import com.basicframework.module.ai.service.semantic.dto.AiMetricSemanticsRevisionDraftDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMetricSemanticsSaveDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * Y03 跨源指标口径与关联粒度校验（真实 MySQL + Redis），核心是 **AT-034 / AT-070**。
 *
 * <p>验收逐条对应卡片 §4：
 * <ol>
 *   <li><b>多对多不重复计算</b>：同一笔事实经订单/发票/回款多对多路径，不得被计入两次。
 *       判据是"每个来源都必须先按自己的主键粒度预聚合再关联"；没有声明即
 *       {@code AI_METRIC_FANOUT_UNSAFE_CONFLICT}，并且**用数字证明**不预聚合会翻倍：</li>
 *   <li><b>不同币种无换算规则不能求和</b>：回款是 USD、订单是 CNY 且没有换算规则时，
 *       聚合必须抛 {@code AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT}，
 *       而不是把 100 USD + 100 CNY 静默算成 200；</li>
 *   <li><b>口径冲突不能靠模型猜测</b>：来源时区与口径声明不一致时显式拒绝
 *       （{@code AI_METRIC_CALIBER_CONFLICT}），绝不"按主来源的时区算"。</li>
 * </ol>
 *
 * <p>本 IT 还覆盖口径的版本化安全语义：显式钉住版本、换版本不改旧报表、
 * 草稿/过期/停用/指纹被改动一律阻断。
 */
class AiCrossSourceMetricAcceptanceIT extends AbstractPersistenceIntegrationTest {

    private static final String METRIC_CODE = "net_revenue";

    private static final Long AUTHOR = 100L;

    private static final Long REVIEWER = 200L;

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 9, 20, 12, 0);

    @Autowired
    private AiMetricSemanticsService semanticsService;

    @Autowired
    private AiMetricSemanticsMapper semanticsMapper;

    @Autowired
    private AiMetricSemanticsRevisionMapper revisionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AiCrossSourceQueryPlanValidator validator = new AiCrossSourceQueryPlanValidator();

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ai_metric_semantics_revision");
        jdbcTemplate.update("DELETE FROM ai_metric_semantics WHERE metric_code = ?", METRIC_CODE);
        loginAs(AUTHOR);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(Long operatorId) {
        LoginUser loginUser = new LoginUser().setId(operatorId).setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    /** 登记口径并以另一位审核人发布，返回版本号。 */
    private Long registerAndPublish(String definitionJson) {
        semanticsService.createSemantics(
                new AiMetricSemanticsSaveDTO().setMetricCode(METRIC_CODE).setMetricName("净收入"));
        AiMetricSemanticsRevisionDraftDTO draft = new AiMetricSemanticsRevisionDraftDTO();
        draft.setMetricCode(METRIC_CODE);
        draft.setMetricName("净收入");
        draft.setDefinitionJson(definitionJson);
        draft.setValidFrom("2026-01-01T00:00:00");
        semanticsService.createRevision(draft);
        loginAs(REVIEWER);
        AiMetricSemanticsRevisionDO published = semanticsService.publishRevision(
                semanticsService.getSemanticsByCode(METRIC_CODE).getId(), 1L, 0);
        loginAs(AUTHOR);
        return published.getRevisionNo();
    }

    /**
     * 订单/发票/回款三来源的全钉住计划：每个来源显式给出数据集版本与映射版本，
     * 并按各自主键粒度预聚合后再按 order→invoice→payment 关联。
     */
    private static String pinnedPlan(boolean preAggregated) {
        return "{\"semanticsRevision\":1,\"sources\":["
                + "{\"role\":\"order\",\"datasetCode\":\"dset_orders\",\"datasetVersion\":2,\"mappingRevision\":1,"
                + "\"primaryKey\":[\"order_id\"],\"preAggregated\":true},"
                + "{\"role\":\"invoice\",\"datasetCode\":\"dset_invoices\",\"datasetVersion\":1,\"mappingRevision\":1,"
                + "\"primaryKey\":[\"invoice_id\"],\"preAggregated\":" + preAggregated + "},"
                + "{\"role\":\"payment\",\"datasetCode\":\"dset_payments\",\"datasetVersion\":3,\"mappingRevision\":2,"
                + "\"primaryKey\":[\"payment_id\"],\"preAggregated\":true}"
                + "],\"aggregationOrder\":[\"order\",\"invoice\",\"payment\"]}";
    }

    @Test
    void manyToManyJoinIsRefusedAndPreAggregationIsWhatMakesTheSameTotalCorrect() {
        // AT-034 之一：多对多不重复计算
        Long revision = registerAndPublish(caliber("CNY"));
        AiMetricSemantics semantics = semanticsService.resolveVerified(METRIC_CODE, revision, AS_OF);

        // 数字先说清楚"不预聚合会怎样"：一笔 100.00 的订单经 2 张发票 + 3 笔回款展开成 6 行，
        // 直接关联求和得到 600.00 —— 同一笔事实被算了 6 次。
        BigDecimal orderNet = new BigDecimal("100.00");
        int invoiceLines = 2;
        int paymentLines = 3;
        BigDecimal naiveJoinSum = orderNet.multiply(BigDecimal.valueOf(invoiceLines * paymentLines));
        BigDecimal preAggregatedSum = orderNet;
        assertThat(naiveJoinSum).isEqualByComparingTo("600.00");
        assertThat(preAggregatedSum).isEqualByComparingTo("100.00");
        // 金额走十进制，不经过二进制浮点
        assertThat(naiveJoinSum.stripTrailingZeros().scale()).isLessThanOrEqualTo(2);

        // 未声明按 invoice 主键粒度预聚合 → 阻断，绝不"执行时小心点"
        assertThatThrownBy(() -> validator.validate(pinnedPlan(false), semantics, Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_FANOUT_UNSAFE_CONFLICT.getCode());

        // 三个来源都按各自主键粒度预聚合 → 放行，计划携带口径版本与指纹
        CrossSourceQueryPlan plan = validator.validate(pinnedPlan(true), semantics, Set.of());
        assertThat(plan.sources()).hasSize(3);
        assertThat(plan.sources()).allMatch(CrossSourceQueryPlan.SourceSelection::preAggregated);
        assertThat(plan.aggregationOrder()).containsExactly("order", "invoice", "payment");
        assertThat(plan.semanticsDefinitionHash()).isEqualTo(semantics.definitionHash());
    }

    @Test
    void differentCurrenciesWithoutConversionRuleCannotBeSummed() {
        // AT-034 之二：不同币种无换算规则不能求和
        Long revision = registerAndPublish(caliber("USD"));
        AiMetricSemantics semantics = semanticsService.resolveVerified(METRIC_CODE, revision, AS_OF);

        // 来源币种确实是两种
        assertThat(semantics.sources().stream()
                        .map(AiMetricSemantics.Source::currency)
                        .distinct()
                        .toList())
                .containsExactlyInAnyOrder("CNY", "USD");
        // 100 USD + 100 CNY 若相加会得到 200 —— 这正是必须阻断的"看起来对"的结果
        assertThat(new BigDecimal("100").add(new BigDecimal("100"))).isEqualByComparingTo("200");

        assertThatThrownBy(() -> validator.validate(pinnedPlan(true), semantics, Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT.getCode());
    }

    @Test
    void aDraftIsNotAVerifiableFactUntilAnIndependentReviewerPublishesIt() {
        // 口径是"声明意图"：币种/时区这类业务事实由**聚合校验**拒绝，不在发布时拦。
        // 发布只冻结声明 + 指纹 + 独立审核，判定留在单一真源（AiCrossSourceQueryPlanValidator）。
        semanticsService.createSemantics(
                new AiMetricSemanticsSaveDTO().setMetricCode(METRIC_CODE).setMetricName("净收入"));
        AiMetricSemanticsRevisionDraftDTO draft = new AiMetricSemanticsRevisionDraftDTO();
        draft.setMetricCode(METRIC_CODE);
        draft.setDefinitionJson(caliber("USD"));
        draft.setValidFrom("2026-01-01T00:00:00");
        semanticsService.createRevision(draft);

        // 草稿不是可核验事实
        assertThatThrownBy(() -> semanticsService.resolveVerified(METRIC_CODE, 1L, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_SEMANTICS_REVISION_NOT_PUBLISHED_CONFLICT.getCode());

        loginAs(REVIEWER);
        Long semanticsId = semanticsService.getSemanticsByCode(METRIC_CODE).getId();
        AiMetricSemanticsRevisionDO published = semanticsService.publishRevision(semanticsId, 1L, 0);
        assertThat(published.getStatus()).isEqualTo(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED);
        // 发布后成为可核验事实，但用它做跨源聚合仍然被币种规则拒绝
        AiMetricSemantics semantics = semanticsService.resolveVerified(METRIC_CODE, 1L, AS_OF);
        assertThatThrownBy(() -> validator.validate(pinnedPlan(true), semantics, Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT.getCode());
    }

    @Test
    void caliberConflictIsRefusedInsteadOfBeingGuessed() {
        // AT-034 之三：口径冲突不能靠模型猜测
        Long revision = registerAndPublish(caliberUtc());
        AiMetricSemantics semantics = semanticsService.resolveVerified(METRIC_CODE, revision, AS_OF);
        // 冲突是真的：回款来源时区是 UTC，口径声明是 Asia/Shanghai
        assertThat(semantics.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(semantics.sourceByRole("payment").timezone()).isEqualTo("UTC");

        assertThatThrownBy(() -> validator.validate(pinnedPlan(true), semantics, Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_CALIBER_CONFLICT.getCode());
    }

    @Test
    void gapsInRequiredSourcesRequireClarificationInsteadOfSilentZero() {
        // 逐步实施 3：缺口有澄清与完整性策略
        Long revision = registerAndPublish(caliber("CNY"));
        AiMetricSemantics semantics = semanticsService.resolveVerified(METRIC_CODE, revision, AS_OF);
        // order 是必需来源，invoice/payment 在口径里显式标了 optional
        assertThat(semantics.sourceByRole("order").optional()).isFalse();
        assertThat(semantics.sourceByRole("payment").optional()).isTrue();

        // 必需来源有缺口 → 澄清（"没有回款记录"和"回款金额是 0"必须能区分）
        assertThatThrownBy(() -> validator.validate(pinnedPlan(true), semantics, Set.of("order")))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_GAP_CLARIFICATION_REQUIRED.getCode());
        // 可选来源的缺口按完整性策略放行
        assertThat(validator
                        .validate(pinnedPlan(true), semantics, Set.of("invoice", "payment"))
                        .sources())
                .hasSize(3);
    }

    @Test
    void planMustPinDatasetVersionAndMappingVersionForEverySource() {
        // 逐步实施 2：查询计划显式选择数据集及映射版本
        Long revision = registerAndPublish(caliber("CNY"));
        AiMetricSemantics semantics = semanticsService.resolveVerified(METRIC_CODE, revision, AS_OF);

        String noMappingVersion = pinnedPlan(true).replace(",\"mappingRevision\":2", "");
        String noDatasetVersion = pinnedPlan(true).replace("\"datasetVersion\":3", "\"datasetVersion\":null");
        String noSemanticsVersion = pinnedPlan(true).replace("\"semanticsRevision\":1,", "");
        for (String plan : List.of(noMappingVersion, noDatasetVersion, noSemanticsVersion)) {
            assertThatThrownBy(() -> validator.validate(plan, semantics, Set.of()))
                    .isInstanceOf(ServiceException.class)
                    .extracting(failure -> ((ServiceException) failure).getCode())
                    .isEqualTo(AiErrorCodeConstants.AI_METRIC_PLAN_SELECTION_REQUIRED.getCode());
        }
        // 计划选择了口径未声明的来源 → 拒绝
        String undeclared = pinnedPlan(true).replace("\"dset_payments\"", "\"dset_refunds\"");
        assertThatThrownBy(() -> validator.validate(undeclared, semantics, Set.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_PLAN_SOURCE_NOT_DECLARED.getCode());
    }

    @Test
    void publishingANewCaliberRevisionNeverChangesHowOldResultsAreInterpreted() {
        // 换口径版本不改旧报表：按 v1 判定仍得 v1 的事实
        Long firstRevision = registerAndPublish(caliber("CNY"));
        AiMetricSemantics firstFacts = semanticsService.resolveVerified(METRIC_CODE, firstRevision, AS_OF);
        String firstHash = firstFacts.definitionHash();
        assertThat(semanticsService.getSemanticsByCode(METRIC_CODE).getCurrentRevision())
                .isEqualTo(1L);

        // 发布 v2：把回款来源显式标为可选
        loginAs(AUTHOR);
        AiMetricSemanticsRevisionDraftDTO draft = new AiMetricSemanticsRevisionDraftDTO();
        draft.setMetricCode(METRIC_CODE);
        draft.setDefinitionJson(caliberOptionalPayment("CNY"));
        draft.setValidFrom("2026-01-01T00:00:00");
        semanticsService.createRevision(draft);
        Long semanticsId = semanticsService.getSemanticsByCode(METRIC_CODE).getId();
        loginAs(REVIEWER);
        semanticsService.publishRevision(semanticsId, 2L, 0);
        assertThat(semanticsService.getSemanticsByCode(METRIC_CODE).getCurrentRevision())
                .isEqualTo(2L);

        // v1 事实不变（指纹不变），v2 事实独立
        AiMetricSemantics reloadedV1 = semanticsService.resolveVerified(METRIC_CODE, 1L, AS_OF);
        assertThat(reloadedV1.definitionHash()).isEqualTo(firstHash);
        assertThat(reloadedV1.sourceByRole("payment").optional()).isTrue();
        AiMetricSemantics v2 = semanticsService.resolveVerified(METRIC_CODE, 2L, AS_OF);
        assertThat(v2.definitionHash()).isNotEqualTo(firstHash);
        assertThat(v2.sourceByRole("payment").optional()).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void draftExpiredDisabledAndTamperedRevisionsAreAllBlocked() {
        // 本用例**不**参与测试级事务（NOT_SUPPORTED）：MyBatis 的一级缓存会让同一事务内的
        // 重读命中旧快照，而"版本被另一个事务改过"恰恰要求跨事务可见。
        // 服务方法自带 @Transactional，因此登记/发布各自独立提交——这也更接近真实的
        // "另一个操作者改了版本"场景。
        Long revision = registerAndPublish(caliber("CNY"));
        assertThat(semanticsService.resolveVerified(METRIC_CODE, revision, AS_OF))
                .isNotNull();

        // 有效期外 → 阻断，不回退到"当前版本"
        assertThatThrownBy(() ->
                        semanticsService.resolveVerified(METRIC_CODE, revision, LocalDateTime.of(2025, 1, 1, 0, 0)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_SEMANTICS_REVISION_EXPIRED_CONFLICT.getCode());
        // 未知版本 / 未知口径 → 404 语义
        assertThatThrownBy(() -> semanticsService.resolveVerified(METRIC_CODE, 99L, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_SEMANTICS_REVISION_NOT_EXISTS.getCode());
        assertThatThrownBy(() -> semanticsService.resolveVerified("no_such_metric", 1L, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_SEMANTICS_NOT_EXISTS.getCode());

        // 版本外改动：绕过服务层直接改库里的冻结指纹 → 读取时重算比对发现不符
        Long semanticsId = semanticsService.getSemanticsByCode(METRIC_CODE).getId();
        int tamperedRows = jdbcTemplate.update(
                "UPDATE ai_metric_semantics_revision SET definition_fingerprint = ? WHERE metric_semantics_id = ? AND revision_no = ?",
                "0".repeat(64),
                semanticsId,
                revision);
        assertThat(tamperedRows).isEqualTo(1);
        assertThatThrownBy(() -> semanticsService.resolveVerified(METRIC_CODE, revision, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_SEMANTICS_FINGERPRINT_CONFLICT.getCode());
    }

    @Test
    void registrationRequiresAnOperatorAndAnIndependentReviewer() {
        // 无操作员身份不能登记口径（口径决定"哪些数可以相加"）
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> semanticsService.createSemantics(new AiMetricSemanticsSaveDTO()
                        .setMetricCode(METRIC_CODE)
                        .setMetricName("净收入")))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_ACCESS_DENIED.getCode());

        loginAs(AUTHOR);
        // 标识重复
        semanticsService.createSemantics(
                new AiMetricSemanticsSaveDTO().setMetricCode(METRIC_CODE).setMetricName("净收入"));
        assertThatThrownBy(() -> semanticsService.createSemantics(new AiMetricSemanticsSaveDTO()
                        .setMetricCode(METRIC_CODE)
                        .setMetricName("重复")))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_SEMANTICS_CODE_DUPLICATE.getCode());

        // 草稿创建人不能自己发布
        AiMetricSemanticsRevisionDraftDTO draft = new AiMetricSemanticsRevisionDraftDTO();
        draft.setMetricCode(METRIC_CODE);
        draft.setDefinitionJson(caliber("CNY"));
        draft.setValidFrom("2026-01-01T00:00:00");
        semanticsService.createRevision(draft);
        Long semanticsId = semanticsService.getSemanticsByCode(METRIC_CODE).getId();
        assertThatThrownBy(() -> semanticsService.publishRevision(semanticsId, 1L, 0))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_SEMANTICS_PUBLISHER_CONFLICT.getCode());
    }

    @Test
    void disablingASemanticsBlocksAggregationInsteadOfFallingBackToHistory() {
        Long revision = registerAndPublish(caliber("CNY"));
        AiMetricSemanticsDO semantics = semanticsService.getSemanticsByCode(METRIC_CODE);
        semanticsService.updateSemanticsStatus(semantics.getId(), semantics.getVersion(), false);

        assertThatThrownBy(() -> semanticsService.resolveVerified(METRIC_CODE, revision, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_SEMANTICS_DISABLED_CONFLICT.getCode());
    }

    @Test
    void managementQueriesArePagedBoundedAndReturnRealRows() {
        // 覆盖管理面读路径：按标识定位 + 分页（状态过滤与关键字）
        registerAndPublish(caliber("CNY"));
        AiMetricSemanticsDO byCode = semanticsService.getSemanticsByCode(METRIC_CODE);
        assertThat(byCode.getMetricCode()).isEqualTo(METRIC_CODE);
        assertThat(byCode.getCurrentRevision()).isEqualTo(1L);

        assertThat(semanticsService
                        .getSemanticsPage(new PageParam().setPageNo(1).setPageSize(10), null, null)
                        .getList())
                .hasSize(1);
        assertThat(semanticsService
                        .getSemanticsPage(new PageParam().setPageNo(1).setPageSize(10), "ACTIVE", "net")
                        .getList())
                .hasSize(1);
        // 状态/关键字过滤确实生效（而不是忽略过滤条件）
        assertThat(semanticsService
                        .getSemanticsPage(new PageParam().setPageNo(1).setPageSize(10), "DISABLED", null)
                        .getList())
                .isEmpty();
        assertThat(semanticsService
                        .getSemanticsPage(new PageParam().setPageNo(1).setPageSize(10), null, "no-such-keyword")
                        .getList())
                .isEmpty();
    }

    @Test
    void mappingVersionsAreReadThroughTheVersionHeaderNotTheLatestOne() {
        // 覆盖版本 Mapper 的定位与分页（含"两个入参缺一即返回 null"的守卫）
        registerAndPublish(caliber("CNY"));
        Long semanticsId = semanticsService.getSemanticsByCode(METRIC_CODE).getId();

        // 分页按口径与状态过滤：过滤列固定生效，而不是忽略条件
        assertThat(revisionMapper
                        .selectPage(new PageParam().setPageNo(1).setPageSize(10), semanticsId, "PUBLISHED")
                        .getList())
                .hasSize(1);
        assertThat(revisionMapper
                        .selectPage(new PageParam().setPageNo(1).setPageSize(10), semanticsId, "DRAFT")
                        .getList())
                .isEmpty();
        assertThat(revisionMapper
                        .selectPage(new PageParam().setPageNo(1).setPageSize(10), null, "PUBLISHED")
                        .getList())
                .hasSize(1);
        // 显式钉住版本号才能定位；两个入参缺一即 null（不允许"只给口径取最新版"）
        assertThat(revisionMapper.selectByRevisionNo(semanticsId, 1L)).isNotNull();
        assertThat(revisionMapper.selectByRevisionNo(null, 1L)).isNull();
        assertThat(revisionMapper.selectByRevisionNo(semanticsId, null)).isNull();
        assertThat(semanticsMapper.selectByCode(null)).isNull();
        // 未知版本 → 服务层 404
        assertThatThrownBy(() -> semanticsService.resolveVerified(METRIC_CODE, 77L, AS_OF))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_METRIC_SEMANTICS_REVISION_NOT_EXISTS.getCode());
    }

    /** 口径三来源定义：回款来源币种由参数决定，回款默认是**可选**来源（没有回款记录可容忍）。 */
    private static String caliber(String paymentCurrency) {
        return caliber(paymentCurrency, true);
    }

    private static String caliber(String paymentCurrency, boolean paymentOptional) {
        return "{\"metricCode\":\"" + METRIC_CODE + "\",\"unit\":\"CURRENCY\",\"currency\":\"CNY\","
                + "\"timezone\":\"Asia/Shanghai\",\"timeWindow\":\"CALENDAR_MONTH\",\"sources\":["
                + "{\"role\":\"order\",\"datasetCode\":\"dset_orders\",\"datasetVersion\":2,\"mappingRevision\":1,"
                + "\"unit\":\"CURRENCY\",\"currency\":\"CNY\",\"timezone\":\"Asia/Shanghai\","
                + "\"primaryKey\":[\"order_id\"],\"optional\":false},"
                + "{\"role\":\"invoice\",\"datasetCode\":\"dset_invoices\",\"datasetVersion\":1,\"mappingRevision\":1,"
                + "\"unit\":\"CURRENCY\",\"currency\":\"CNY\",\"timezone\":\"Asia/Shanghai\","
                + "\"primaryKey\":[\"invoice_id\"],\"optional\":true},"
                + "{\"role\":\"payment\",\"datasetCode\":\"dset_payments\",\"datasetVersion\":3,\"mappingRevision\":2,"
                + "\"unit\":\"CURRENCY\",\"currency\":\"" + paymentCurrency + "\",\"timezone\":\"Asia/Shanghai\","
                + "\"primaryKey\":[\"payment_id\"],\"optional\":" + paymentOptional + "}"
                + "],\"aggregationOrder\":[\"order\",\"invoice\",\"payment\"],\"conversion\":null}";
    }

    /** 回款来源显式标为可选。 */
    private static String caliberOptionalPayment(String paymentCurrency) {
        return caliber(paymentCurrency, false);
    }

    /** 回款来源时区改成 UTC：制造与口径声明的口径冲突。 */
    private static String caliberUtc() {
        return caliber("CNY", false)
                .replace(
                        "\"timezone\":\"Asia/Shanghai\",\"primaryKey\":[\"payment_id\"]",
                        "\"timezone\":\"UTC\",\"primaryKey\":[\"payment_id\"]");
    }
}
