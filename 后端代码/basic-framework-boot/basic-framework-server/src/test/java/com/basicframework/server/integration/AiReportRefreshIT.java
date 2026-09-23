package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportRefreshDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.dal.mysql.report.AiReportRefreshMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.job.AiReportRefreshJob;
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
import com.basicframework.module.ai.service.report.refresh.AiReportRefreshService;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshRequestDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshResultDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshStateDTO;
import com.basicframework.module.ai.service.run.AiRunQueryExecutionService;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionRequestDTO;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.server.fixtures.ai.AiGoldenSetFixture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * R06 报表刷新（真实 MySQL + 真实 A01–A07/D03–D06 链路）。
 *
 * <p>版本里的固定计划由**真实规划器**产出（脚本化模型），刷新再按当前权限重放它：
 * 因此"原子切换""失败保留旧结果并留痕""重复刷新幂等""停用源即停""失权后连旧结果都不读"
 * 都是端到端事实，而不是桩返回值。
 */
@Import({AiReportRefreshIT.ScriptedModel.class, AiReportRefreshIT.ResolverConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiReportRefreshIT extends AbstractPersistenceIntegrationTest {

    /** 固定模型夹具：只返回黄金集的合规计划（真实模型不可复现）。 */
    @TestConfiguration
    static class ScriptedModel {

        @Bean
        @Primary
        AiQueryPlanModel scriptedQueryPlanModel() {
            return (endpointId, prompt, jsonSchema) ->
                    "{\"kind\":\"PLAN\",\"plan\":" + AiGoldenSetFixture.EAST_AUGUST_PLAN + "}";
        }
    }

    @TestConfiguration
    static class ResolverConfiguration {

        @Bean
        SubjectScopeResolver refreshScopeResolver() {
            return request -> Optional.of(new SubjectScope(
                    Set.of(10L),
                    Set.of(AiGoldenSetFixture.DATASET_CODE),
                    request.scopeSource(),
                    request.scopeVersion()));
        }
    }

    private static final String APP_CODE = "it-r06-app";

    private static final String ALICE = "alice";

    private static final String CONNECTOR_CODE = "it-r06-connector";

    private static final String READ_ONLY_PASSWORD = "r06-it-readonly-password";

    private static final String CATALOG = "golden_catalog";

    private static final QueryScope C001 = QueryScope.eq("customer_id", "C001");

    @Autowired
    private AiReportRefreshService refreshService;

    @Autowired
    private AiReportRefreshMapper refreshMapper;

    @Autowired
    private AiReportRefreshJob refreshJob;

    @Autowired
    private AiReportService reportService;

    @Autowired
    private AiRunQueryExecutionService queryExecutionService;

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

    private Long datasetVersionId;

    private Long grantId;

    private Long reportId;

    private String fixedPlanJson;

    private String fixedResultRef;

    @BeforeEach
    void prepare() {
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS " + CATALOG);
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".v_sales_golden");
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + CATALOG + ".v_payments_golden");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".payments");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + CATALOG + ".orders");
        AiGoldenSetFixture.ORDERS_DDL.forEach(jdbcTemplate::execute);
        AiGoldenSetFixture.DATA_DML.forEach(jdbcTemplate::update);
        jdbcTemplate.execute("DROP USER IF EXISTS 'r06_ro'@'%'");
        jdbcTemplate.execute("CREATE USER 'r06_ro'@'%' IDENTIFIED BY '" + READ_ONLY_PASSWORD + "'");
        jdbcTemplate.execute("GRANT SELECT ON " + CATALOG + ".* TO 'r06_ro'@'%'");
        jdbcTemplate.execute("FLUSH PRIVILEGES");

        connectorId = connectorService.create(new AiConnectorSaveDTO()
                .setCode(CONNECTOR_CODE)
                .setName("R06 只读库")
                .setConnectorType(AiConnectorDO.TYPE_MYSQL)
                .setConfigJson("{\"host\":\"localhost\",\"port\":" + mysqlMappedPort() + ",\"database\":\"" + CATALOG
                        + "\",\"username\":\"r06_ro\",\"sslMode\":\"REQUIRED\",\"allowedObjects\":[\""
                        + AiGoldenSetFixture.SOURCE_OBJECT + "\"]}")
                .setCredential(READ_ONLY_PASSWORD));
        datasetId = publishDataset();
        datasetVersionId = latestVersionId();

        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("R06 报表应用")
                .setOrigins(List.of("https://r06.example.com")));
        applicationId = issue.getApplication().getId();
        appSecret = issue.getSecret();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, ALICE, ALICE, "crm-auth", 1L);
        grantId = grantService.createGrant(
                applicationId, "USER", ALICE, "DATASET", "dset_" + AiGoldenSetFixture.DATASET_CODE, Set.of("READ"));

        loginAsAlice();
        // 固定查询版本：用真实规划器产出规范化计划（创建时已校验），刷新只按当前权限重放它
        AiRunQueryExecutionResultDTO executed = queryExecutionService.execute(new AiRunQueryExecutionRequestDTO()
                .setDatasetId(datasetId)
                .setDatasetVersionId(datasetVersionId)
                .setEndpointId(1L)
                .setQuestion("按客户看 8 月净销售额")
                .setRowScope(C001));
        fixedPlanJson = executed.getPlanJson();
        fixedResultRef = executed.getResultRef();

        reportId = reportService.create(new AiReportSaveDTO()
                .setCode("it_r06_sales")
                .setName("华东 8 月销售（可刷新）")
                .setDescription("R06 集成用例")
                .setMode(AiReportDO.MODE_REFRESHABLE)
                .setSchemaVersion("1.0")
                .setSpecJson(spec(executed))
                .setSourcesJson("[{\"resourceType\":\"DATASET\",\"resourceKey\":\"dset_"
                        + AiGoldenSetFixture.DATASET_CODE + "\"}]")
                .setCompleteness("COMPLETE")
                .setVersion(0));
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
                    "DELETE FROM ai_report_refresh WHERE report_id IN (SELECT id FROM ai_report WHERE application_id = ?)",
                    applicationId);
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
        jdbcTemplate.execute("DROP USER IF EXISTS 'r06_ro'@'%'");
        applicationId = null;
        connectorId = null;
        datasetId = null;
        datasetVersionId = null;
        grantId = null;
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

    /** 可刷新报表的规格：数据来自固定计划，数据集引用锚定真实执行结果。 */
    private String spec(AiRunQueryExecutionResultDTO executed) {
        return """
               {"schemaVersion":"1.0","title":"华东 8 月销售","themeRef":{"themeId":"thm_default","revision":1},
                "layout":{"columns":12,"gap":16,"items":[
                  {"blockId":"intro","row":0,"column":0,"span":12},
                  {"blockId":"sales_chart","row":1,"column":0,"span":12}]},
                "blocks":[
                  {"id":"intro","title":"统计口径","type":"text","text":"2026 年 8 月、华东、按客户汇总净额。"},
                  {"id":"sales_chart","title":"客户净销售额","type":"chart","datasetRef":"sales_result",
                   "chart":{"chartType":"column","categoryField":"customer_name","valueField":"total_net_amount","legend":false}}],
                "datasetRefs":[
                  {"id":"sales_result","resultRef":"%s","queryRef":"sales_query",
                   "columns":[{"field":"customer_name","label":"客户","dataType":"STRING"},
                              {"field":"total_net_amount","label":"净销售额","dataType":"DECIMAL","unit":"CURRENCY"}],
                   "rowCount":%d,"completeness":"%s"}],
                "queryRefs":[{"id":"sales_query","plan":%s}],
                "sources":[{"id":"sales_source","kind":"DATASET","resourceId":"golden-sales","resourceVersion":1,
                   "queryRef":"sales_query","description":"黄金集合成数据集"}]}
               """
                .formatted(executed.getResultRef(), executed.getRowCount(), executed.getCompleteness(), fixedPlanJson);
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

    private AiReportRefreshRequestDTO request() {
        return new AiReportRefreshRequestDTO().setReportId(reportId).setRowScope(C001);
    }

    private static void assertCode(Throwable throwable, Integer expectedCode) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expectedCode);
    }

    private int versionCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_report_version WHERE report_id = ?", Integer.class, reportId);
    }

    private int attemptCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_report_refresh WHERE report_id = ?", Integer.class, reportId);
    }

    @Test
    void refreshExecutesFixedPlanAtomicallyAndIsIdempotent() {
        AiReportRefreshResultDTO refreshed = refreshService.refresh(request());

        assertThat(refreshed.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_OK);
        assertThat(refreshed.getResultVersionNo()).isEqualTo(2);
        assertThat(refreshed.getDataJson()).contains("alice").contains("290.00");
        assertThat(versionCount()).isEqualTo(2);

        // 生效版本已切换；可刷新版本仍不存数据（数据在刷新尝试上）
        AiReportVersionDO current = reportService.readCurrent(reportId);
        assertThat(current.getVersionNo()).isEqualTo(2);
        assertThat(current.getDataJson()).isNull();
        assertThat(current.getAsOf()).isNotNull();
        assertThat(current.getSpecJson()).contains(planHash());

        // 原版本不被改写
        AiReportVersionDO first = reportService.getVersion(reportId, 1);
        assertThat(first.getVersionNo()).isEqualTo(1);
        assertThat(first.getAsOf()).isNull();

        // 重复刷新幂等：上游数据未变化时不产生第三个版本
        AiReportRefreshResultDTO again = refreshService.refresh(request());
        assertThat(again.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_UNCHANGED);
        assertThat(versionCount()).isEqualTo(2);
        assertThat(attemptCount()).isEqualTo(2);
    }

    @Test
    void failedRefreshKeepsOldResultAndMarksTimeAndReason() {
        refreshService.refresh(request());
        String lastOkData = refreshService.lastState(reportId).getDataJson();

        // 停用来源：刷新失败并留痕（时间 + 稳定原因），旧结果保持可读
        datasetService.updateStatus(
                datasetId, datasetService.getDataset(datasetId).getVersion(), false);
        AiReportRefreshResultDTO failed = refreshService.refresh(request());

        assertThat(failed.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_FAILED);
        assertThat(failed.getReason()).isEqualTo(String.valueOf(AiErrorCodeConstants.AI_DATASET_DISABLED.getCode()));
        assertThat(failed.getAsOf()).isNotNull();
        assertThat(versionCount()).isEqualTo(2);

        AiReportRefreshDO attempt = refreshMapper.selectLastByReport(reportId);
        assertThat(attempt.getStatus()).isEqualTo(AiReportRefreshDO.STATUS_FAILED);
        assertThat(attempt.getReason()).isEqualTo(failed.getReason());
        assertThat(attempt.getAsOf()).isNotNull();

        AiReportRefreshStateDTO state = refreshService.lastState(reportId);
        assertThat(state.getStatus()).isEqualTo(AiReportRefreshDO.STATUS_FAILED);
        assertThat(state.getReason()).isEqualTo(failed.getReason());
        // 上一次成功的结果仍可读（范围仍被授权覆盖）
        assertThat(state.getDataJson()).isEqualTo(lastOkData);
        assertThat(state.getResultVersionNo()).isEqualTo(2);
    }

    @Test
    void revokedScopeStopsRefreshAndRefusesReadingOldResult() {
        refreshService.refresh(request());

        grantService.revokeGrant(grantId, grantService.getGrant(grantId).getVersion());

        // 失权：刷新与读取旧结果都停止（AT-048）
        assertCode(
                assertThatThrownBy(() -> refreshService.refresh(request())).actual(),
                AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED.getCode());
        assertCode(
                assertThatThrownBy(() -> refreshService.lastState(reportId)).actual(),
                AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED.getCode());
        // 失权是前置条件失败：不落刷新尝试、不新增版本
        assertThat(attemptCount()).isEqualTo(1);
        assertThat(versionCount()).isEqualTo(2);
    }

    @Test
    void refreshWithoutAuthorizedRowScopeIsRecordedAsFailure() {
        AiReportRefreshResultDTO result = refreshService.refresh(request().setRowScope(null));

        assertThat(result.getStatus()).isEqualTo(AiReportRefreshResultDTO.STATUS_FAILED);
        assertThat(result.getReason())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_REPORT_REFRESH_SCOPE_REQUIRED.getCode()));
        assertThat(versionCount()).isEqualTo(1);
        assertThat(refreshMapper.selectLastByReport(reportId).getStatus()).isEqualTo(AiReportRefreshDO.STATUS_FAILED);
    }

    @Test
    void snapshotReportCannotBeRefreshed() {
        loginAsAlice();
        Long snapshotReportId = reportService.create(new AiReportSaveDTO()
                .setCode("it_r06_snapshot")
                .setName("快照报表")
                .setMode(AiReportDO.MODE_SNAPSHOT)
                .setSchemaVersion("1.0")
                .setSpecJson(spec(executedResult()))
                .setDataJson("{\"kind\":\"REPORT\"}")
                .setSourcesJson("[{\"resourceType\":\"DATASET\",\"resourceKey\":\"dset_"
                        + AiGoldenSetFixture.DATASET_CODE + "\"}]")
                .setCompleteness("COMPLETE")
                .setVersion(0));

        assertCode(
                assertThatThrownBy(() ->
                                refreshService.refresh(new AiReportRefreshRequestDTO().setReportId(snapshotReportId)))
                        .actual(),
                AiErrorCodeConstants.AI_REPORT_REFRESH_NOT_SUPPORTED.getCode());
    }

    /** 固定计划里的计划哈希（新版本必须沿用同一份计划）。 */
    private String planHash() {
        return String.valueOf(
                com.basicframework.framework.common.util.json.JsonUtils.parseObject(fixedPlanJson, Map.class)
                        .get("planHash"));
    }

    /** 复用固定计划对应的执行结果（仅用于构造快照报表的规格）。 */
    private AiRunQueryExecutionResultDTO executedResult() {
        return queryExecutionService.execute(new AiRunQueryExecutionRequestDTO()
                .setDatasetId(datasetId)
                .setDatasetVersionId(datasetVersionId)
                .setEndpointId(1L)
                .setQuestion("按客户看 8 月净销售额")
                .setRowScope(C001));
    }

    @Test
    void refreshJobOnlyPicksReportsThatAreDue() {
        refreshService.refresh(request());

        // 刚刷新过（尝试记录在间隔内）：不再到期
        assertThat(refreshMapper.selectDueReportIds(1800, 50)).doesNotContain(reportId);
        // 把尝试时间回拨到间隔之外：重新到期（到期判定基于尝试记录的时间）
        jdbcTemplate.update(
                "UPDATE ai_report_refresh SET as_of = DATE_SUB(NOW(), INTERVAL 2 HOUR) WHERE report_id = ?", reportId);
        assertThat(refreshMapper.selectDueReportIds(1800, 50)).contains(reportId);

        String summary = refreshJob.execute("");
        assertThat(summary).contains("扫描").contains("未变化");
        // 作业没有行范围上下文：数据类刷新失败留痕，不产生新版本
        assertThat(versionCount()).isEqualTo(2);
    }

    @Test
    void anotherSubjectCannotRefreshOrReadState() {
        refreshService.refresh(request());

        subjectService.syncSubject(applicationId, AiSubjectType.USER, "bob", "bob", "crm-auth", 1L);
        loginAs("bob");

        assertCode(
                assertThatThrownBy(() -> refreshService.refresh(request())).actual(),
                AiErrorCodeConstants.AI_REPORT_NOT_FOUND.getCode());
        assertCode(
                assertThatThrownBy(() -> refreshService.lastState(reportId)).actual(),
                AiErrorCodeConstants.AI_REPORT_NOT_FOUND.getCode());
    }

    /** 以指定主体登录（复用同一应用的票据）。 */
    private void loginAs(String subject) {
        var ticket = ticketService.issue(
                APP_CODE, appSecret, AiSubjectType.USER, subject, List.of(AiGoldenSetFixture.DATASET_CODE));
        LoginUser loginUser = new LoginUser()
                .setId(ticketService.verify(ticket.getToken()).getTicketId())
                .setUserType(UserTypeEnum.MEMBER.getValue())
                .setInfo(Map.of(
                        AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID,
                        String.valueOf(applicationId),
                        AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE,
                        "USER",
                        AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID,
                        subject,
                        AiUserSessionCommonApi.INFO_KEY_SCOPE_FINGERPRINT,
                        ticket.getScopeFingerprint()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
}
