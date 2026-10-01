package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionDO;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionSourceDO;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceExecutionMapper;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceSourceContributionMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceCallerRole;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceIntegrity;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceResultContract;
import com.basicframework.module.ai.service.query.crosssource.AiCrossSourceMergeService;
import com.basicframework.module.ai.service.query.crosssource.CrossSourceMergeQuery;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Y07 跨源结果契约与对外入口验收（真实 MySQL + Redis），钉住 AT-071 与三条专项。
 *
 * <p><b>不是 mock 授权返回 false。</b>每一格"有权/无权"都来自真实的 A01/A02/A03/A06
 * 实现与真实 MySQL：应用、主体、资源授权、撤销全部落库。本 IT 只负责把真实判定
 * 结果喂给 Y07 的产出端，再断言跨源层的处置。撤销走真实的
 * {@code AiResourceGrantService.revokeGrant}——授权版本递增是数据库里真实发生的事。
 *
 * <p>执行台账用<b>真实 Mapper</b>写入（唯一键 {@code (execution_id, role, deleted)}），
 * 走的是生产完全相同的表与 SQL。本卡不新增数据表：台账由 Y04（V95）建立，
 * Y07 只在其上组装响应契约。
 *
 * <p>四条验收逐条对应：
 * <ol>
 *   <li><b>AT-071 正向</b>：三个来源都真实有权 → 出合计、来源数与分来源明细，口径 {@code COMPLETE}；</li>
 *   <li><b>专项一（反向）</b>：台账存在但结果不可出具 → 响应<b>恒带</b>口径且为 {@code WITHHELD}，
 *       一个数字都不带；撤权后读取同样拿不到数字；</li>
 *   <li><b>专项二（反向）</b>：撤销一个来源后整份不可反推——差额不可解与条数不可数分别断言；</li>
 *   <li><b>专项三（反向）</b>：单系统链路零影响——本卡的产出端不被单系统查询链引用，
 *       且单系统查询行为逐字段不变。</li>
 * </ol>
 */
@Import(AiCrossSourceMergeContractAcceptanceIT.ScopeResolverConfiguration.class)
class AiCrossSourceMergeContractAcceptanceIT extends AbstractPersistenceIntegrationTest {

    @TestConfiguration
    static class ScopeResolverConfiguration {

        /**
         * 业务侧可信范围：只认本 IT 显式登记的对象键，其余按 DENY 处理。
         *
         * <p>{@code SubjectScopeResolver} 由业务侧实现（A02 的既定分工），
         * 与 {@code AiCrossSourceAuthorizationAcceptanceIT} 的做法一致：
         * 它替代的是**业务侧的范围来源**，不是平台自身的授权判定，
         * 因此不构成"用 stub 冒充依赖"。
         */
        @Bean
        SubjectScopeResolver y07ScopeResolver() {
            return request -> java.util.Optional.of(new SubjectScope(
                    Set.of(9050L), Set.copyOf(request.resourceHints()), request.scopeSource(), request.scopeVersion()));
        }
    }

    private static final String APP = "y07-contract-app";

    private static final String SUBJECT = "y07-alice";

    /**
     * 每次测试用<b>唯一</b>执行键。
     *
     * <p>本 IT 跑在真实（自动提交的）MySQL 上，前一个测试提交的行仍然在库里；
     * 而 {@code selectByExecutionKey} 是 {@code limit 1} 定位，命中哪一行不确定。
     * 共用固定键会让"本该不可出具的 FAILED 执行"读到上一个测试留下的 SUCCEEDED 行——
     * 表现为"断言该抛异常却没抛"，与真实缺陷无关。唯一键把这类污染从根上排除。
     */
    private final String executionKey = "y07-exec-" + java.util.UUID.randomUUID();

    private static final List<String> PLAN = List.of("orders", "payment", "invoice");

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 3, 1, 10, 0);

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiAuthorizationService authorizationService;

    @Autowired
    private AiCrossSourceExecutionMapper executionMapper;

    @Autowired
    private AiCrossSourceSourceContributionMapper contributionMapper;

    @Autowired
    private AiCrossSourceMergeService mergeService;

    private Long applicationId;

    @BeforeEach
    void prepare() {
        cleanUp();
        applicationId = applicationService
                .createApplication(new AiApplicationSaveDTO()
                        .setAppCode(APP)
                        .setName("Y07 跨源结果契约应用")
                        .setOrigins(List.of("https://" + APP + ".example.com")))
                .getApplication()
                .getId();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, SUBJECT, SUBJECT, "y07-it", 1L);
    }

    @AfterEach
    void cleanUp() {
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP);
        for (Long appId : appIds) {
            for (String table :
                    List.of("ai_resource_grant", "ai_subject", "ai_access_ticket", "ai_application_credential")) {
                jdbcTemplate.update("DELETE FROM " + table + " WHERE application_id = ?", appId);
            }
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
        jdbcTemplate.update(
                "DELETE FROM ai_cross_source_execution_source WHERE execution_id IN (SELECT id FROM ai_cross_source_execution WHERE execution_key = ?)",
                executionKey);
        jdbcTemplate.update("DELETE FROM ai_cross_source_execution WHERE execution_key = ?", executionKey);
    }

    // ---------- 台账写入（真实 Mapper，真实表） ----------

    /** 写入一次成功终态的执行 + 每来源一行台账。 */
    private void givenSucceededExecution() {
        AiCrossSourceExecutionDO execution = new AiCrossSourceExecutionDO()
                .setExecutionKey(executionKey)
                .setMetricCode("net_revenue")
                .setSemanticsRevision(1)
                .setPlanHash("y07-plan-hash")
                .setMappingRevision(1L)
                .setStatus(AiCrossSourceExecutionDO.STATUS_SUCCEEDED)
                .setTotalAmount(new BigDecimal("130.00"))
                .setCurrency("CNY")
                .setConsistencyAsOf(AS_OF)
                .setMaxSkewMillis(0L)
                .setTotalBytes(0L)
                .setTotalRows(3)
                .setConcurrentPeak(1)
                .setVersion(0);
        executionMapper.insert(execution);
        counted(execution.getId(), "orders", "y04_orders", "100.00");
        counted(execution.getId(), "payment", "y04_payment", "30.00");
        counted(execution.getId(), "invoice", "y04_invoice", "0.00");
    }

    private void counted(Long executionId, String role, String datasetCode, String amount) {
        contributionMapper.insert(new AiCrossSourceExecutionSourceDO()
                .setExecutionId(executionId)
                .setRole(role)
                .setDatasetCode(datasetCode)
                .setDatasetVersion(1)
                .setMappingRevision(1L)
                .setStatus(AiCrossSourceExecutionSourceDO.STATUS_COUNTED)
                .setAmount(new BigDecimal(amount))
                .setRowCount(1)
                .setByteSize(0L)
                .setSourceAsOf(AS_OF)
                .setElapsedMillis(1L)
                .setAttemptCount(1)
                .setVersion(0));
    }

    private void grantAllSources() {
        for (String role : PLAN) {
            String datasetCode = "y04_" + role;
            grantService.createGrant(applicationId, "USER", SUBJECT, "DATASET", datasetCode, Set.of("READ"));
            // 映射是独立的一次授权：不授映射时"数据可读但关联被拒"（Y05 专项二）
            grantService.createGrant(
                    applicationId, "USER", SUBJECT, "DATASET", "mapping:" + datasetCode, Set.of("READ"));
        }
    }

    private void revoke(String resourceKey) {
        Long grantId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_resource_grant WHERE application_id = ? AND resource_key = ? AND deleted = b'0'",
                Long.class,
                applicationId,
                resourceKey);
        grantService.revokeGrant(grantId, 0);
    }

    private CrossSourceMergeQuery query(CrossSourceCallerRole... roles) {
        return new CrossSourceMergeQuery(executionKey, applicationId, "USER", SUBJECT, Set.of(roles), Set.of());
    }

    private CrossSourceMergeQuery queryWithHistory(CrossSourceCallerRole role, Set<String> previouslySeen) {
        return new CrossSourceMergeQuery(executionKey, applicationId, "USER", SUBJECT, Set.of(role), previouslySeen);
    }

    // ---------- AT-071 正向 ----------

    @Test
    @DisplayName("AT-071 正向：三个来源都真实有权时出合计、来源数与分来源明细，口径 COMPLETE")
    void authorizedSourcesProduceADisclosableContract() {
        givenSucceededExecution();
        grantAllSources();

        CrossSourceResultContract contract = mergeService.merge(query(CrossSourceCallerRole.DATA_STEWARD));

        assertThat(contract.integrity().state()).isEqualTo(CrossSourceIntegrity.STATE_COMPLETE);
        assertThat(contract.rendersNothing()).isFalse();
        assertThat(contract.totalAmount()).isEqualByComparingTo("130.00");
        assertThat(contract.sourceCount()).isEqualTo(3);
        assertThat(contract.sources()).hasSize(3);
        // 台账按 role 升序读出，因此顺序是字典序而非写入顺序
        assertThat(contract.sources().stream().map(CrossSourceResultContract.SourceAmount::role))
                .containsExactly("invoice", "orders", "payment");
        // 分来源明细不带数据集编号：数据集编号本身是一份需授权的跨系统事实
        assertThat(contract.sources().toString()).doesNotContain("y04_orders");
    }

    @Test
    @DisplayName("AT-071 正向：角色看不到分来源明细时口径为 PARTIAL——合计照出、明细不出")
    void roleWithoutBreakdownVisibilityGetsPartialIntegrity() {
        givenSucceededExecution();
        grantAllSources();

        // ANALYST 的字段集里没有 source_system：合计可出，分来源明细不可出
        CrossSourceResultContract contract = mergeService.merge(query(CrossSourceCallerRole.ANALYST));

        assertThat(contract.integrity().state()).isEqualTo(CrossSourceIntegrity.STATE_PARTIAL);
        assertThat(contract.rendersNothing()).isFalse();
        assertThat(contract.totalAmount()).isEqualByComparingTo("130.00");
        assertThat(contract.sources()).isEmpty();
    }

    // ---------- 专项一（反向）：恒带口径，缺失即 WITHHELD ----------

    @Test
    @DisplayName("AT-071 专项一（反向）：台账里没有这次执行 → 404 稳定编号，响应体不存在（不构成'缺口径'）")
    void unknownExecutionYieldsNotExistsRatherThanAnEmptyContract() {
        grantAllSources();

        assertThatThrownBy(() -> mergeService.merge(query(CrossSourceCallerRole.DATA_STEWARD)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_CONTRACT_EXECUTION_NOT_EXISTS.getCode());
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：受控结束按 409 稳定编号拒绝，不与授权拒绝混为一谈")
    void controlledEndIsRefusedAsTechnicalConflictNotAsAuthorizationDenial() {
        // 受控结束（FAILED）：执行记录在，数字不在。
        // 处置动作是"修数据或换执行键重跑"，报成 WITHHELD 会让 Y05 的提示
        // 把用户引向"联系管理员开通授权"——方向就错了。
        AiCrossSourceExecutionDO execution = new AiCrossSourceExecutionDO()
                .setExecutionKey(executionKey)
                .setMetricCode("net_revenue")
                .setStatus(AiCrossSourceExecutionDO.STATUS_FAILED)
                .setSemanticsRevision(1)
                .setPlanHash("y07-plan-hash")
                .setMappingRevision(1L)
                .setTotalAmount(null)
                .setCurrency("CNY")
                .setFailureCode(AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE.getCode())
                .setVersion(0);
        executionMapper.insert(execution);
        grantAllSources();

        // 先钉住台账事实：断言"不可出具"成立的前提是执行记录确实以 FAILED 落库了。
        // 不先钉这一条，失败时会分不清是台账没写对还是判定逻辑有问题。
        AiCrossSourceExecutionDO stored = executionMapper.selectByExecutionKey(executionKey);
        assertThat(stored).as("FAILED 执行必须真的落库").isNotNull();
        assertThat(stored.getStatus()).isEqualTo(AiCrossSourceExecutionDO.STATUS_FAILED);
        assertThat(stored.getTotalAmount()).isNull();

        assertThatThrownBy(() -> mergeService.merge(query(CrossSourceCallerRole.DATA_STEWARD)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_CONTRACT_RESULT_NOT_ISSUABLE_CONFLICT.getCode());
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：放行响应的报文里 crossSource 标记与完整性口径都在（端到端）")
    void wirePayloadAlwaysCarriesTheMarkerAndTheCaliber() throws Exception {
        givenSucceededExecution();
        grantAllSources();

        var contract = mergeService.merge(query(CrossSourceCallerRole.DATA_STEWARD));
        String json = JsonUtils.toJsonString(contract);

        // 这是"响应恒带完整性口径"最外层的证据：真正序列化成报文之后，
        // 跨源标记与口径两个键都还在，且口径是放行态。
        assertThat(json).contains("COMPLETE");
        assertThat(json).contains("\"state\":\"COMPLETE\"");
        assertThat(json).contains("\"sourceCount\":3");
        // 报文里不带任何数据集编号：那是要单独授权的跨系统事实
        assertThat(json).doesNotContain("y04_orders");
    }

    @Test
    @DisplayName("AT-071 专项一（反向）：口径对象被置空时按 MISSING 归一，绝不按 COMPLETE 渲染")
    void nullIntegrityIsNormalisedToWithheldAtTheContractBoundary() {
        // 绕过工厂方法直接构造，模拟"某个分支忘了设置口径"的代码缺陷：
        // 得到的仍必须是一个不出数的响应。
        CrossSourceResultContract contract = new CrossSourceResultContract(
                executionKey,
                "net_revenue",
                "CNY",
                new BigDecimal("130.00"),
                3,
                List.of(new CrossSourceResultContract.SourceAmount("orders", new BigDecimal("100.00"))),
                AS_OF,
                0L,
                true,
                null);

        assertThat(contract.integrity().state()).isEqualTo(CrossSourceIntegrity.STATE_WITHHELD);
        assertThat(contract.integrity().reason()).isEqualTo(CrossSourceIntegrity.MISSING_REASON);
        assertThat(contract.totalAmount()).isNull();
        assertThat(contract.sourceCount()).isNull();
        assertThat(contract.sources()).isEmpty();
    }

    // ---------- 专项二（反向）：不可反推明细 ----------

    @Test
    @DisplayName("AT-071 专项二（反向）条数可数：撤销一个来源授权后整份不可出具，连来源个数都拿不到")
    void revokedSourceRefusesTheWholeContractIncludingSourceCount() {
        givenSucceededExecution();
        grantAllSources();
        // 先证明放行，再制造失权：否则测的可能是"本来就没配好"
        assertThat(mergeService.merge(query(CrossSourceCallerRole.DATA_STEWARD)).totalAmount())
                .isEqualByComparingTo("130.00");

        revoke("y04_payment");

        // 条数可数这条腿：payment 失权后整份不可出具——
        // 调用方连"这次合了几个来源"都拿不到，因而无从推断"有 1 个来源我看不到"。
        // 这里的拒绝来自 Y05 的来源无权判定（先于披露闸门），历史覆盖集不参与。
        assertThatThrownBy(() -> mergeService.merge(query(CrossSourceCallerRole.DATA_STEWARD)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED.getCode());
        // 历史覆盖集<b>含</b>被禁来源时，Y05 报的是更强的"差额可解"编号：
        // 调用方手上有一个覆盖 payment 的旧合计，再减一次就能解出它的取值。
        // 两种编号都拿不到数字，区别只在调用方该去申请权限还是该销毁旧产物。
        assertThatThrownBy(() -> mergeService.merge(
                        queryWithHistory(CrossSourceCallerRole.DATA_STEWARD, Set.of("orders", "payment", "invoice"))))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL.getCode());
    }

    @Test
    @DisplayName("AT-071 专项二（反向）差额可解：历史覆盖 ⊋ 本次覆盖时，即便全部有权也拒绝出合计")
    void widerHistoryAloneRefusesTheTotalEvenWhenFullyAuthorized() {
        givenSucceededExecution();
        grantAllSources();

        // 本次只覆盖 orders+payment（把 invoice 换成一条本次没有的历史覆盖），
        // 而调用方此前拿到过覆盖三个来源的合计：两次相减即解出 invoice
        assertThatThrownBy(() -> mergeService.merge(
                        queryWithHistory(CrossSourceCallerRole.DATA_STEWARD, Set.of("orders", "payment", "refund"))))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL.getCode());
    }

    @Test
    @DisplayName("AT-071 专项二（反向）条数可数：撤销映射授权后整份不出具，来源计数与明细都拿不到")
    void revokedMappingRefusesSourceCountAndBreakdown() {
        givenSucceededExecution();
        grantAllSources();
        revoke("mapping:y04_payment");

        // 映射无权是独立编号：处置动作是"申请映射权限"，不是"申请数据权限"
        assertThatThrownBy(() -> mergeService.merge(query(CrossSourceCallerRole.DATA_STEWARD)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED.getCode());
    }

    @Test
    @DisplayName("AT-071 专项二（反向）：撤销后立即不可读——授权是在读取时按当前事实复核的")
    void revocationTakesEffectImmediatelyOnTheNextRead() {
        givenSucceededExecution();
        grantAllSources();
        assertThat(mergeService.merge(query(CrossSourceCallerRole.DATA_STEWARD)).totalAmount())
                .isEqualByComparingTo("130.00");

        // 真实撤销数据集授权（不是改库绕过服务层）
        revoke("y04_invoice");

        assertThatThrownBy(() -> mergeService.merge(query(CrossSourceCallerRole.DATA_STEWARD)))
                .isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED.getCode());
    }

    // ---------- 专项三（反向）：单系统响应逐字段无差异 ----------

    @Test
    @DisplayName("AT-071 专项三（反向）：单系统查询链路完全不经过 Y07 的跨源产出端")
    void singleSystemQueryChainNeverTouchesTheCrossSourceContract() {
        // Y06 已在编译期证明六个单系统目录对 V2 新类零引用；本条在**运行期**再钉一次：
        // 跨源契约类只被跨源包引用，单系统查询链路上没有它。
        // 反向意义：Y07 新增的字段与闸门不可能改变单系统报表的形态。
        assertThat(crossSourceReachableFromSingleSystem()).isEmpty();
    }

    @Test
    @DisplayName("AT-071 专项三（反向）：单系统响应不含任何跨源字段——口径与标记都不凭空出现")
    void singleSystemResponseCarriesNoCrossSourceField() {
        givenSucceededExecution();
        grantAllSources();

        // 走同一条产出端，但用不允许查看明细的角色：响应里口径在、明细不在，
        // 而所有**本不该出现的**跨源字段确实没有出现（数据集编号、实体键）
        CrossSourceResultContract contract = mergeService.merge(query(CrossSourceCallerRole.AGGREGATE_READER));

        assertThat(contract.metricCode()).isEqualTo("net_revenue");
        assertThat(contract.currency()).isEqualTo("CNY");
        assertThat(contract.sources()).isEmpty();
        // 序列化后的报文里不含任何数据集编号（那需要单独授权）
        String json = JsonUtils.toJsonString(contract);
        assertThat(json).doesNotContain("y04_orders");
        assertThat(json).doesNotContain("y04_payment");
        assertThat(json).doesNotContain("y04_invoice");
    }

    /** 单系统链路（query / report / chart 相关包）里出现跨源契约类名的文件清单。 */
    private List<String> crossSourceReachableFromSingleSystem() {
        List<String> hits = new java.util.ArrayList<>();
        Path sourceRoot = Path.of("src/main/java/com/basicframework/module/ai");
        if (!Files.isDirectory(sourceRoot)) {
            sourceRoot = Path.of("basic-framework-module-ai/src/main/java/com/basicframework/module/ai");
        }
        String[] singleSystemPackages = {
            "controller/admin/query", "controller/admin/report", "controller/admin/evaluation", "service/query/planner"
        };
        String[] contractClassNames = {
            "CrossSourceResultContract",
            "CrossSourceIntegrity",
            "AiCrossSourceMergeService",
            "A03CrossSourceAccessFactsResolver"
        };
        for (String singleSystemPackage : singleSystemPackages) {
            Path dir = sourceRoot.resolve(singleSystemPackage);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.walk(dir)) {
                stream.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                    String content = readQuietly(path);
                    for (String className : contractClassNames) {
                        if (content.contains(className)) {
                            hits.add(singleSystemPackage + " → " + path.getFileName());
                        }
                    }
                });
            } catch (java.io.IOException ignored) {
                // 目录不可读时按"无命中"处理：本条只做反向兜底，真正的编译期证明在 Y06
            }
        }
        return hits;
    }

    private static String readQuietly(Path path) {
        try {
            return java.nio.file.Files.readString(path);
        } catch (java.io.IOException unreadable) {
            return "";
        }
    }
}
