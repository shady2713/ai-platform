package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalResultDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalRunDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import com.basicframework.module.ai.dal.mysql.evaluation.AiEvalResultMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.evaluation.AiEvalRunService;
import com.basicframework.module.ai.service.evaluation.AiEvalSuiteDigests;
import com.basicframework.module.ai.service.evaluation.AiEvalSuiteService;
import com.basicframework.module.ai.service.evaluation.dto.AiEvalCaseSaveDTO;
import com.basicframework.module.ai.service.evaluation.dto.AiEvalSuiteSaveDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceSaveDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q04 评测套件、执行器与可复现报告端到端（真实 MySQL，写入提交）。
 *
 * <p>本环境没有可用模型端点与出站许可，因此执行路径验证的是**如实失败**：
 * 逐例记为 ERROR + 稳定错误码，不伪造判定；规则核验与摘要/复核由单测与下面的用例覆盖。
 * 真实模型评测报告属未验证项（见本卡证据）。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiEvalSuiteIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-eval-app";

    private static final String ENDPOINT_NAME = "it-eval-endpoint";

    private static final String CHECKS_MONEY = "[{\"kind\":\"MONEY\",\"path\":\"amount\",\"expected\":\"450.00\"}]";

    @Autowired
    private AiEvalSuiteService suiteService;

    @Autowired
    private AiEvalRunService runService;

    @Autowired
    private AiEvalResultMapper resultMapper;

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private AiResourceGrantService grantService;

    @Autowired
    private AiServiceService serviceService;

    @Autowired
    private AiModelEndpointService endpointService;

    @AfterEach
    void cleanUp() {
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            jdbcTemplate.update(
                    "DELETE FROM ai_eval_result WHERE run_id IN (SELECT id FROM ai_eval_run WHERE application_id = ?)",
                    appId);
            jdbcTemplate.update("DELETE FROM ai_eval_run WHERE application_id = ?", appId);
            jdbcTemplate.update(
                    "DELETE FROM ai_eval_case WHERE suite_id IN (SELECT id FROM ai_eval_suite WHERE application_id = ?)",
                    appId);
            jdbcTemplate.update("DELETE FROM ai_eval_suite WHERE application_id = ?", appId);
            jdbcTemplate.update(
                    "DELETE FROM ai_run_event WHERE run_id IN (SELECT id FROM ai_run WHERE application_id = ?)", appId);
            jdbcTemplate.update(
                    "DELETE FROM ai_run_task WHERE run_id IN (SELECT id FROM ai_run WHERE application_id = ?)", appId);
            jdbcTemplate.update("DELETE FROM ai_run_idempotency WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_run WHERE application_id = ?", appId);
            List<Long> serviceIds =
                    jdbcTemplate.queryForList("SELECT id FROM ai_service WHERE app_id = ?", Long.class, appId);
            for (Long id : serviceIds) {
                jdbcTemplate.update("DELETE FROM ai_service_resource WHERE service_id = ?", id);
                jdbcTemplate.update("DELETE FROM ai_service_release WHERE service_id = ?", id);
                jdbcTemplate.update("DELETE FROM ai_service WHERE id = ?", id);
            }
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
        List<Long> endpointIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_model_endpoint WHERE name = ?", Long.class, ENDPOINT_NAME);
        for (Long endpointId : endpointIds) {
            jdbcTemplate.update("DELETE FROM ai_model_probe WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint_revision WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint WHERE id = ?", endpointId);
        }
    }

    /** 建应用 + 主体 + 授权 + 草稿服务（评测受理会在"没有可用发布"处如实失败）。 */
    private long[] prepareApplicationAndService() {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 评测应用")
                .setOrigins(List.of("https://eval.example.com")));
        Long applicationId = issue.getApplication().getId();
        applicationService.updateStatus(applicationId, 0, true);
        subjectService.syncSubject(applicationId, AiSubjectType.APP, null, "IT 评测应用", "it-owner", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "eval-runner", "评测合成主体", "it-scope", 1L);
        grantService.createGrant(applicationId, "APP", null, "REPORT", "report-1", Set.of("READ"));
        grantService.createGrant(applicationId, "USER", "eval-runner", "REPORT", "report-1", Set.of("READ"));

        AiModelEndpointSaveDTO endpointSave = new AiModelEndpointSaveDTO();
        endpointSave.setName(ENDPOINT_NAME);
        endpointSave.setProvider("openai_compatible");
        endpointSave.setBaseUrl("https://it-eval.invalid/v1");
        endpointSave.setModelId("gpt-4o-mini");
        endpointSave.setCapabilities(List.of("TEXT"));
        endpointSave.setCredential("sk-it-eval");
        Long endpointId = endpointService.createEndpoint(endpointSave);
        var endpoint = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, endpoint.getVersion(), true);
        var revision = endpointService.getRevisions(endpointId).get(0);
        jdbcTemplate.update(
                "INSERT INTO ai_model_probe (endpoint_id, config_revision, credential_revision, probe_kind, status,"
                        + " detail_code, latency_ms, creator, updater) VALUES (?, ?, 1, 'TEXT', 'SUPPORTED', NULL, 5,"
                        + " 'it', 'it')",
                endpointId,
                revision.getRevision());

        Long serviceId = serviceService.createDraft(new AiServiceSaveDTO()
                .setAppId(applicationId)
                .setCode("order-qa")
                .setName("订单问答")
                .setModelEndpointId(endpointId)
                .setPromptTemplate("你是订单助手")
                .setInputSchema("{\"type\":\"object\"}")
                .setRequiredCapabilities(List.of("TEXT"))
                .setRunSubjectType("USER")
                .setEvalThreshold(0));
        return new long[] {applicationId, serviceId};
    }

    private Long createSuite(Long applicationId, Long serviceId, String dataLevel) {
        return suiteService.createSuite(new AiEvalSuiteSaveDTO()
                .setApplicationId(applicationId)
                .setCode("order-qa-eval")
                .setName("订单问答评测")
                .setServiceId(serviceId)
                .setSubjectType("USER")
                .setExternalUserId("eval-runner")
                .setDataLevel(dataLevel));
    }

    private Long createCase(Long suiteId, String caseKey, String checksJson) {
        return suiteService.createCase(new AiEvalCaseSaveDTO()
                .setSuiteId(suiteId)
                .setCaseKey(caseKey)
                .setTitle("金额核对 " + caseKey)
                .setSeverity(AiEvalCaseDO.SEVERITY_BLOCKER)
                .setQuestion("上海地区上个月净额是多少？")
                .setExpectVersion("endpoint:1@1")
                .setChecksJson(checksJson)
                .setNeedsReview(false));
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void suiteLifecycleFreezesDigestAndBlocksEditsUntilNewRevision() {
        long[] ids = prepareApplicationAndService();
        Long suiteId = createSuite(ids[0], ids[1], AiEvalSuiteDO.LEVEL_INTERNAL);
        createCase(suiteId, "case_amount", CHECKS_MONEY);

        AiEvalSuiteDO draft = suiteService.requireSuite(suiteId);
        assertThat(draft.getStatus()).isEqualTo(AiEvalSuiteDO.STATUS_DRAFT);
        assertThat(draft.getRevision()).isEqualTo(1);

        suiteService.freezeSuite(suiteId, draft.getVersion());
        AiEvalSuiteDO frozen = suiteService.requireSuite(suiteId);
        assertThat(frozen.getStatus()).isEqualTo(AiEvalSuiteDO.STATUS_FROZEN);
        assertThat(frozen.getRevision()).as("冻结即新修订").isEqualTo(2);
        assertThat(frozen.getCaseCount()).isEqualTo(1);
        assertThat(frozen.getFrozenTime()).isNotNull();
        assertThat(frozen.getContentDigest()).hasSize(64);

        // 冻结后编辑被拒绝（要改必须先建新修订）
        AiEvalCaseDO frozenCase = suiteService.listCases(suiteId).get(0);
        assertThatThrownBy(() -> suiteService.updateCase(new AiEvalCaseSaveDTO()
                        .setId(frozenCase.getId())
                        .setVersion(frozenCase.getVersion())
                        .setSuiteId(suiteId)
                        .setTitle("改标题")
                        .setQuestion("换个问题")))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_EVAL_SUITE_FROZEN));
        assertThatThrownBy(() -> suiteService.freezeSuite(suiteId, frozen.getVersion() + 10))
                .as("拿过期版本冻结被拒绝")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_STATE_CONFLICT));

        suiteService.newRevision(suiteId, frozen.getVersion());
        AiEvalSuiteDO revised = suiteService.requireSuite(suiteId);
        assertThat(revised.getStatus()).isEqualTo(AiEvalSuiteDO.STATUS_DRAFT);
        assertThat(revised.getRevision()).isEqualTo(3);
    }

    @Test
    void dataLevelAndForeignServiceAreRejectedAtCreation() {
        long[] ids = prepareApplicationAndService();
        assertThatThrownBy(() -> createSuite(ids[0], ids[1], "L3_PERSONAL"))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_EVAL_DATA_LEVEL_NOT_ALLOWED));
        assertThatThrownBy(() -> createSuite(ids[0] + 9_999L, ids[1], AiEvalSuiteDO.LEVEL_INTERNAL))
                .as("跨应用服务不能被评测")
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
        assertThatThrownBy(() -> createSuite(ids[0], ids[1] + 9_999L, AiEvalSuiteDO.LEVEL_INTERNAL))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_REQUEST_INVALID));
    }

    @Test
    void runFreezesSnapshotAndReportsHonestErrorsWithoutModelEndpoint() {
        long[] ids = prepareApplicationAndService();
        Long suiteId = createSuite(ids[0], ids[1], AiEvalSuiteDO.LEVEL_INTERNAL);
        createCase(suiteId, "case_amount", CHECKS_MONEY);
        AiEvalSuiteDO draft = suiteService.requireSuite(suiteId);
        suiteService.freezeSuite(suiteId, draft.getVersion());
        String frozenDigest = suiteService.requireSuite(suiteId).getContentDigest();

        Long runId = runService.startRun(suiteId);

        AiEvalRunDO run = runService.requireRun(runId);
        assertThat(run.getStatus()).isEqualTo(AiEvalRunDO.STATUS_COMPLETED);
        assertThat(run.getCaseTotal()).isEqualTo(1);
        assertThat(run.getSuiteDigest()).as("运行行冻结套件摘要").isEqualTo(frozenDigest);
        assertThat(run.getSuiteRevision()).isEqualTo(2);
        assertThat(run.getFinishedTime()).isNotNull();

        List<AiEvalResultDO> results = runService.listResults(runId);
        assertThat(results).hasSize(1);
        AiEvalResultDO result = results.get(0);
        assertThat(result.getStatus()).as("没有可用发布/端点时如实记为 ERROR，不伪造判定").isEqualTo(AiEvalResultDO.STATUS_ERROR);
        assertThat(result.getFailureCode()).isNotNull();
        assertThat(result.getCaseDigest()).hasSize(64);
        assertThat(result.getResultDigest()).hasSize(64);
        assertThat(run.getErrorCount()).isEqualTo(1);
        assertThat(run.getPassedCount()).isZero();
        assertThat(run.getFailedCount()).isZero();

        // 套件修改不改变已有 eval 运行：新修订 + 改期望值后，运行快照与结果摘要都不变
        LocalDigests before = new LocalDigests(run.getSuiteDigest(), result.getCaseDigest(), result.getResultDigest());
        suiteService.newRevision(suiteId, suiteService.requireSuite(suiteId).getVersion());
        AiEvalCaseDO editable = suiteService.listCases(suiteId).get(0);
        suiteService.updateCase(new AiEvalCaseSaveDTO()
                .setId(editable.getId())
                .setVersion(editable.getVersion())
                .setSuiteId(suiteId)
                .setTitle(editable.getTitle())
                .setQuestion(editable.getQuestion())
                .setChecksJson("[{\"kind\":\"MONEY\",\"path\":\"amount\",\"expected\":\"290.00\"}]"));

        // 分页查询路径（管理端列表）：服务端过滤 + 计数
        assertThat(suiteService
                        .pageSuites(new com.basicframework.framework.common.pojo.PageParam(), ids[0], null)
                        .getTotal())
                .isEqualTo(1);
        assertThat(runService
                        .pageRuns(new com.basicframework.framework.common.pojo.PageParam(), suiteId)
                        .getTotal())
                .isEqualTo(1);
        assertThat(runService
                        .pageResults(
                                new com.basicframework.framework.common.pojo.PageParam(),
                                runId,
                                AiEvalResultDO.STATUS_ERROR)
                        .getTotal())
                .isEqualTo(1);

        AiEvalRunDO afterEdit = runService.requireRun(runId);
        assertThat(afterEdit.getSuiteDigest()).isEqualTo(before.suiteDigest());
        assertThat(runService.listResults(runId).get(0).getCaseDigest()).isEqualTo(before.caseDigest());
        assertThat(runService.listResults(runId).get(0).getResultDigest()).isEqualTo(before.resultDigest());
        String report = runService.report(runId);
        assertThat(report).contains("\"suiteDigest\":\"" + before.suiteDigest() + "\"");
        assertThat(report).as("报告不含合成问题正文与提示词").doesNotContain("上海地区上个月净额是多少？");
        AiEvalSuiteDO revisedSuite = suiteService.requireSuite(suiteId);
        assertThat(AiEvalSuiteDigests.of(revisedSuite, suiteService.listCases(suiteId)))
                .as("改过期望规则的新修订摘要确实变了（否则上面的断言没有意义）")
                .isNotEqualTo(before.suiteDigest());
    }

    /** 便于断言的三元组。 */
    private record LocalDigests(String suiteDigest, String caseDigest, String resultDigest) {}

    @Test
    void reviewOnlyAcceptsPendingResultsAndRewritesVerdictDigest() {
        long[] ids = prepareApplicationAndService();
        Long suiteId = createSuite(ids[0], ids[1], AiEvalSuiteDO.LEVEL_INTERNAL);
        createCase(suiteId, "case_amount", CHECKS_MONEY);
        AiEvalSuiteDO draft = suiteService.requireSuite(suiteId);
        suiteService.freezeSuite(suiteId, draft.getVersion());
        Long runId = runService.startRun(suiteId);

        // 人工复核路径：插入一条等待复核的结果（真实模型不可用，无法从执行路径产出 PASSED）
        resultMapper.insert(new AiEvalResultDO()
                .setRunId(runId)
                .setCaseId(null)
                .setCaseKey("case_review")
                .setSeverity(AiEvalCaseDO.SEVERITY_MAJOR)
                .setStatus(AiEvalResultDO.STATUS_REVIEW_REQUIRED)
                .setCaseDigest("d".repeat(64))
                .setResultDigest("e".repeat(64))
                .setVerdictJson("[{\"kind\":\"MONEY\",\"passed\":true}]")
                .setReviewStatus(AiEvalResultDO.REVIEW_PENDING));
        AiEvalResultDO pending = resultMapper.selectByRunAndCase(runId, "case_review");
        assertThat(pending).as("等待复核的结果必须落库").isNotNull();
        Long pendingId = pending.getId();

        runService.review(pendingId, true, "人工核对金额与引用后通过");
        AiEvalResultDO approved = runService.requireResult(pendingId);
        assertThat(approved.getReviewStatus()).isEqualTo(AiEvalResultDO.REVIEW_APPROVED);
        assertThat(approved.getStatus()).isEqualTo(AiEvalResultDO.STATUS_PASSED);
        assertThat(approved.getReviewedTime()).isNotNull();
        assertThat(approved.getReviewedBy()).isNotNull();
        assertThat(approved.getResultDigest()).as("复核改变最终判定，因此结果摘要随之更新").isNotEqualTo("e".repeat(64));

        assertThatThrownBy(() -> runService.review(pendingId, false, "重复复核"))
                .satisfies(exception -> assertCode(exception, AiErrorCodeConstants.AI_EVAL_RESULT_NOT_REVIEWABLE));
    }
}
