package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.connector.AiConnectorService;
import com.basicframework.module.ai.service.connector.AiMysqlConnectorService;
import com.basicframework.module.ai.service.connector.dto.AiConnectorSaveDTO;
import com.basicframework.module.ai.service.dataset.AiDatasetService;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetSaveDTO;
import com.basicframework.module.ai.service.dataset.dto.AiDatasetVersionSaveDTO;
import com.basicframework.module.ai.service.query.planner.AiQueryPlanModel;
import com.basicframework.module.ai.service.report.persistence.AiReportService;
import com.basicframework.module.ai.service.report.persistence.dto.AiReportSaveDTO;
import com.basicframework.module.ai.service.report.revision.AiReportRevisionModel;
import com.basicframework.module.ai.service.report.revision.AiReportRevisionService;
import com.basicframework.module.ai.service.report.revision.dto.AiReportRevisionRequestDTO;
import com.basicframework.module.ai.service.report.revision.dto.AiReportRevisionResultDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.server.fixtures.ai.AiGoldenSetFixture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * R05 报表对话修改（真实 MySQL + 真实 A01–A07/D03–D06 链路）。
 *
 * <p>本套件不 mock 数据链路：受控查询走真实规划器（脚本化模型）→ 真实编译器 → 真实只读账号，
 * 因此"数据类修改必须重新查询""行范围真的拼进了 WHERE""展示类修改不查库"都是端到端事实。
 * 模型由夹具脚本驱动（真实模型不可复现）。
 */
@Import({AiReportRevisionIT.ScriptedModels.class, AiReportRevisionIT.ResolverConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiReportRevisionIT extends AbstractPersistenceIntegrationTest {

    /** 固定模型评测夹具：查询计划与修订计划都按脚本返回。 */
    @TestConfiguration
    static class ScriptedModels {

        /** 查询计划脚本：plan / clarification。 */
        static final AtomicReference<String> QUERY_SCRIPT = new AtomicReference<>("plan");

        /** 修订脚本：chart-type / grain。 */
        static final AtomicReference<String> REVISION_SCRIPT = new AtomicReference<>("chart-type");

        /** 运行期数据集编号（由用例写入）。 */
        static final AtomicReference<Long> DATASET_ID = new AtomicReference<>();

        @Bean
        @Primary
        AiQueryPlanModel scriptedQueryPlanModel() {
            return (endpointId, prompt, jsonSchema) -> "clarification".equals(QUERY_SCRIPT.get())
                    ? """
                      {"kind":"CLARIFICATION","clarification":{"question":"“销售额”指净额还是含退款金额？",
                       "candidates":[{"code":"total_net_amount","label":"净额"}]}}
                      """
                    : "{\"kind\":\"PLAN\",\"plan\":" + AiGoldenSetFixture.EAST_AUGUST_PLAN + "}";
        }

        @Bean
        @Primary
        AiReportRevisionModel scriptedRevisionModel() {
            return (baseSpecJson, catalog, instruction) -> switch (REVISION_SCRIPT.get()) {
                case "grain" ->
                    "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_GRAIN\",\"datasetId\":\""
                            + DATASET_ID.get() + "\",\"grain\":\"WEEK\"}]}";
                default ->
                    "{\"schemaVersion\":\"1.0\",\"operations\":[{\"op\":\"SET_CHART_TYPE\","
                            + "\"blockId\":\"sales_chart\",\"value\":\"line\"}]}";
            };
        }
    }

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver reportRevisionScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L),
                    Set.of(AiGoldenSetFixture.DATASET_CODE),
                    request.scopeSource(),
                    request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-r05-app";

    private static final String ALICE = "alice";

    private static final String CONNECTOR_CODE = "it-r05-connector";

    private static final String READ_ONLY_PASSWORD = "r05-it-readonly-password";

    /** 合成只读库（夹具 DDL 固定使用 golden_catalog，连接器与只读账号都指向它）。 */
    private static final String CATALOG = "golden_catalog";

    /** 基础版本规格：文本 + 图表两块，引用受控查询结果。 */
    private static final String BASE_SPEC =
            """
            {"schemaVersion":"1.0","title":"华东 8 月销售","themeRef":{"themeId":"thm_default","revision":1},
             "layout":{"columns":12,"gap":16,"items":[
               {"blockId":"intro","row":0,"column":0,"span":12},
               {"blockId":"sales_chart","row":1,"column":0,"span":12}]},
             "blocks":[
               {"id":"intro","title":"统计口径","type":"text","text":"2026 年 8 月、华东、按客户汇总净额。"},
               {"id":"sales_chart","title":"客户净销售额","type":"chart","datasetRef":"sales_result",
                "chart":{"chartType":"column","categoryField":"customer_name","valueField":"total_net_amount","legend":false}}],
             "datasetRefs":[
               {"id":"sales_result","resultRef":"run_r05_base/result/0","queryRef":"sales_query",
                "columns":[{"field":"customer_name","label":"客户","dataType":"STRING"},
                           {"field":"total_net_amount","label":"净销售额","dataType":"DECIMAL","unit":"CURRENCY"}],
                "rowCount":2,"completeness":"COMPLETE"}],
             "queryRefs":[{"id":"sales_query","plan":{"schemaVersion":"1.0","datasetId":"dset_golden-sales",
                "datasetVersion":1,"metrics":["total_net_amount"],"dimensions":["customer_name"],
                "filters":[],"orderBy":[],"limit":10}}],
             "sources":[{"id":"sales_source","kind":"DATASET","resourceId":"golden-sales","resourceVersion":1,
                "queryRef":"sales_query","description":"黄金集合成数据集"}]}
            """;

    /** 基础版本数据：保存时的结果行（客户二 450 / 客户一 290）。 */
    private static final String BASE_DATA =
            """
            {"kind":"REPORT","title":"华东 8 月销售",
             "datasets":[{"datasetRef":"sales_result",
               "columns":[{"field":"customer_name","label":"客户","dataType":"STRING"},
                          {"field":"total_net_amount","label":"净销售额","dataType":"DECIMAL","unit":"CURRENCY"}],
               "rows":[{"customer_name":"客户二","total_net_amount":"450.00"},
                       {"customer_name":"客户一","total_net_amount":"290.00"}],
               "completeness":"COMPLETE"}],
             "data":[{"blockId":"sales_chart","type":"chart","verified":true,"value":null,"rows":[],
               "points":[{"customer_name":"客户二","total_net_amount":"450.00"},
                         {"customer_name":"客户一","total_net_amount":"290.00"}]}],
             "sources":[{"datasetRef":"sales_result","queryRef":"sales_query","resultRef":"run_r05_base/result/0",
                         "rowCount":2,"completeness":"COMPLETE"}],
             "notes":[]}
            """;

    private static final String BASE_SOURCES =
            "[{\"resourceType\":\"DATASET\",\"resourceKey\":\"dset_" + AiGoldenSetFixture.DATASET_CODE + "\"}]";

    @Autowired
    private AiReportRevisionService revisionService;

    @Autowired
    private AiReportService reportService;

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiTicketService ticketService;

    @Autowired
    private AiConnectorService connectorService;

    @Autowired
    private AiMysqlConnectorService mysqlConnectorService;

    @Autowired
    private AiDatasetService datasetService;

    private Long applicationId;

    private String appSecret;

    private Long connectorId;

    private Long datasetId;

    private Long reportId;

    /** 前置：合成只读库 + 应用/主体/授权 + 基础版本报表（真实 D04/D03 链路）。 */
    @BeforeEach
    void prepare() {
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + CATALOG);
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".v_sales_golden");
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".v_payments_golden");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".payments");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        AiGoldenSetFixture.ORDERS_DDL.forEach(jdbcTemplate::execute);
        AiGoldenSetFixture.DATA_DML.forEach(jdbcTemplate::update);
        jdbcTemplate.execute("DROP USER IF EXISTS 'r05_ro'@'%'");
        jdbcTemplate.execute("CREATE USER 'r05_ro'@'%' IDENTIFIED BY '" + READ_ONLY_PASSWORD + "'");
        jdbcTemplate.execute("GRANT SELECT ON " + CATALOG + ".* TO 'r05_ro'@'%'");
        jdbcTemplate.execute("FLUSH PRIVILEGES");

        connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CONNECTOR_CODE)
                .setName("R05 只读库")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlMappedPort() + ",\"database\":\"" + CATALOG
                        + "\",\"username\":\"r05_ro\",\"sslMode\":\"REQUIRED\",\"allowedObjects\":[\""
                        + AiGoldenSetFixture.SOURCE_OBJECT + "\"]}")
                .setCredential(READ_ONLY_PASSWORD));
        datasetId = publishDataset();
        ScriptedModels.DATASET_ID.set(datasetId);

        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("R05 报表应用")
                .setOrigins(List.of("https://r05.example.com")));
        applicationId = issue.getApplication().getId();
        appSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, ALICE, ALICE, "crm-auth", 1L);
        grantService.createGrant(
                applicationId, "USER", ALICE, "DATASET", "dset_" + AiGoldenSetFixture.DATASET_CODE, Set.of("READ"));

        loginAsAlice();
        reportId = reportService.create(new AiReportSaveDTO()
                .setCode("it_r05_sales")
                .setName("华东 8 月销售")
                .setDescription("R05 集成用例")
                .setMode(AiReportDO.MODE_SNAPSHOT)
                .setSchemaVersion("1.0")
                .setSpecJson(BASE_SPEC)
                .setDataJson(BASE_DATA)
                .setSourcesJson(BASE_SOURCES)
                .setCompleteness("COMPLETE")
                .setVersion(0));
        ScriptedModels.QUERY_SCRIPT.set("plan");
        ScriptedModels.REVISION_SCRIPT.set("chart-type");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (connectorId != null) {
            mysqlConnectorService.closePool(connectorId);
        }
        if (applicationId != null) {
            jdbcTemplate.update(
                    "DELETE FROM ai_report_version WHERE report_id IN (SELECT id FROM ai_report WHERE application_id = ?)",
                    applicationId);
            jdbcTemplate.update("DELETE FROM ai_report WHERE application_id = ?", applicationId);
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", applicationId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", applicationId);
            jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", applicationId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", applicationId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", applicationId);
        }
        jdbcTemplate.update(
                "DELETE FROM ai_dataset_version WHERE dataset_id IN (SELECT id FROM ai_dataset WHERE code = ?)",
                AiGoldenSetFixture.DATASET_CODE);
        jdbcTemplate.update("DELETE FROM ai_dataset WHERE code = ?", AiGoldenSetFixture.DATASET_CODE);
        jdbcTemplate.update("DELETE FROM ai_connector WHERE code = ?", CONNECTOR_CODE);
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".v_sales_golden");
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".v_payments_golden");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".payments");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + CATALOG);
        jdbcTemplate.execute("DROP USER IF EXISTS 'r05_ro'@'%'");
        applicationId = null;
        connectorId = null;
        datasetId = null;
        reportId = null;
    }

    private Long publishDataset() {
        Long id = datasetService.create(new AiDatasetSaveDTO()
                .setCode(AiGoldenSetFixture.DATASET_CODE)
                .setName(AiGoldenSetFixture.DATASET_CODE)
                .setConnectorId(connectorId)
                .setSourceObject(AiGoldenSetFixture.SOURCE_OBJECT));
        Long versionId = datasetService.createVersion(
                new AiDatasetVersionSaveDTO().setDatasetId(id).setDefinitionJson(AiGoldenSetFixture.DEFINITION));
        datasetService.verifyVersion(
                versionId, datasetService.getVersion(versionId).getVersion());
        datasetService.publishVersion(
                versionId, datasetService.getVersion(versionId).getVersion());
        return id;
    }

    private Long latestVersionId() {
        return datasetService
                .getVersionPage(datasetId, new PageParam())
                .getList()
                .get(0)
                .getId();
    }

    private void loginAsAlice() {
        var ticket = ticketService.issue(
                APP_CODE, appSecret, AiSubjectType.USER, ALICE, List.of(AiGoldenSetFixture.DATASET_CODE));
        LoginUser loginUser = new LoginUser()
                .setId(ticketService.verify(ticket.getToken()).getTicketId())
                .setUserType(UserTypeEnum.MEMBER.getValue())
                .setInfo(Map.of(
                        AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID,
                        String.valueOf(applicationId),
                        AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE,
                        "USER",
                        AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID,
                        ALICE,
                        AiUserSessionCommonApi.INFO_KEY_SCOPE_FINGERPRINT,
                        ticket.getScopeFingerprint()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private AiReportRevisionRequestDTO request() {
        return new AiReportRevisionRequestDTO()
                .setReportId(reportId)
                .setBaseVersionNo(1)
                .setVersion(0)
                .setInstruction("按周汇总")
                .setEndpointId(1L)
                .setDatasetId(datasetId)
                .setDatasetVersionId(latestVersionId())
                .setRowScope(QueryScope.eq("customer_id", "C001"));
    }

    private static void assertCode(Throwable throwable, Integer expectedCode) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expectedCode);
    }

    @Test
    void presentationRevisionReusesStoredRowsWithoutQuerying() {
        // 断开数据源：展示类修改若真去查库，这里会失败（"换图不重复查库"的端到端证明）
        mysqlConnectorService.closePool(connectorId);
        jdbcTemplate.execute("DROP USER IF EXISTS 'r05_ro'@'%'");
        datasetService.updateStatus(
                datasetId, datasetService.getDataset(datasetId).getVersion(), false);

        AiReportRevisionResultDTO result = revisionService.revise(
                request().setDatasetId(null).setDatasetVersionId(null).setInstruction("把柱状图换成折线图"));

        assertThat(result.getOutcome()).isEqualTo(AiReportRevisionResultDTO.OUTCOME_APPLIED);
        assertThat(result.isQueryPerformed()).isFalse();
        assertThat(result.getNewVersionNo()).isEqualTo(2);
        assertThat(result.getDiff().modifiedBlocks()).containsExactly("sales_chart");

        // 新版本：换图类型，数据仍是保存时的那两行（数字没变）
        AiReportVersionDO current = reportService.readCurrent(reportId);
        assertThat(current.getVersionNo()).isEqualTo(2);
        assertThat(current.getSpecJson()).contains("\"chartType\":\"line\"");
        assertThat(current.getDataJson()).contains("客户二").contains("450.00").contains("290.00");

        // 原版本不被改写：第 1 版仍是柱状图与原始数据
        AiReportVersionDO first = reportService.getVersion(reportId, 1);
        assertThat(first.getSpecJson()).contains("\"chartType\":\"column\"");
        assertThat(first.getSpecJson()).isEqualTo(BASE_SPEC);
        assertThat(first.getDataJson()).isEqualTo(BASE_DATA);
        assertThat(reportService.listVersions(reportId)).hasSize(2);
    }

    @Test
    void dataRevisionExecutesControlledQueryWithRowScopeAndKeepsHistory() {
        ScriptedModels.REVISION_SCRIPT.set("grain");

        AiReportRevisionResultDTO result = revisionService.revise(request());

        assertThat(result.isQueryPerformed()).isTrue();
        assertThat(result.getDiff().queryRequired()).isTrue();
        assertThat(result.getNewVersionNo()).isEqualTo(2);

        AiReportVersionDO current = reportService.readCurrent(reportId);
        // 行范围真的拼进了 WHERE：只返回 C001（alice 290.00），没有 C002 的 bob 450.00
        assertThat(current.getDataJson()).contains("alice").contains("290.00").doesNotContain("bob");
        assertThat(current.getDataJson()).contains("plan_");
        assertThat(current.getCompleteness()).isEqualTo("COMPLETE");
        // 新数据集依赖进入依赖清单（保存时逐项 A03 再鉴权）
        assertThat(current.getSourcesJson()).contains("dset_" + AiGoldenSetFixture.DATASET_CODE);
        // 数据集引用被受控查询结果替换（行数从 2 变成 1）
        assertThat(current.getSpecJson()).contains("\"rowCount\":1");

        // 原版本不被改写：第 1 版仍是保存时的两行与 COMPLETE
        AiReportVersionDO first = reportService.getVersion(reportId, 1);
        assertThat(first.getDataJson()).isEqualTo(BASE_DATA);
        assertThat(first.getSpecJson()).isEqualTo(BASE_SPEC);
    }

    @Test
    void dataRevisionWithoutAuthorizedRowScopeIsRefusedAndHistoryIntact() {
        ScriptedModels.REVISION_SCRIPT.set("grain");

        assertCode(
                assertThatThrownBy(() -> revisionService.revise(request().setRowScope(null)))
                        .actual(),
                AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED.getCode());

        // 拒绝时不产生新版本，历史不动
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_report_version WHERE report_id = ?", Integer.class, reportId))
                .isEqualTo(1);
        assertThat(reportService.readCurrent(reportId).getVersionNo()).isEqualTo(1);
    }

    @Test
    void concurrentRevisionIsRejectedByOptimisticLock() {
        // 第一次修订成功（版本 0 → 1）
        revisionService.revise(request().setInstruction("把柱状图换成折线图"));

        // 第二次用同一个（已过期）乐观锁版本：409，且不产生第三个版本
        assertCode(
                assertThatThrownBy(() -> revisionService.revise(request().setInstruction("再改一次")))
                        .actual(),
                AiErrorCodeConstants.AI_STATE_CONFLICT.getCode());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_report_version WHERE report_id = ?", Integer.class, reportId))
                .isEqualTo(2);
        assertThat(reportService.readCurrent(reportId).getVersionNo()).isEqualTo(2);
    }

    @Test
    void clarificationCreatesNoVersion() {
        ScriptedModels.QUERY_SCRIPT.set("clarification");
        ScriptedModels.REVISION_SCRIPT.set("grain");

        AiReportRevisionResultDTO result = revisionService.revise(request());

        assertThat(result.getOutcome()).isEqualTo(AiReportRevisionResultDTO.OUTCOME_CLARIFICATION);
        assertThat(result.getClarificationQuestion()).contains("销售额");
        assertThat(result.getClarificationCandidates()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_report_version WHERE report_id = ?", Integer.class, reportId))
                .isEqualTo(1);
    }
}
