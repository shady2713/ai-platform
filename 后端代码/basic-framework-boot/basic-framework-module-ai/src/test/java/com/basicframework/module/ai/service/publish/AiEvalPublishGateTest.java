package com.basicframework.module.ai.service.publish;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_BELOW_THRESHOLD;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_BLOCKER_CASE_FAILED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_CASE_COVERAGE_INCOMPLETE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_CASE_NOT_CONVERGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_RELEASE_MISMATCH;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_RUN_NOT_FINISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_SUITE_CHANGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalResultDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalRunDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceReleaseMapper;
import com.basicframework.module.ai.service.evaluation.AiEvalRunService;
import com.basicframework.module.ai.service.evaluation.AiEvalSuiteService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceEvaluationSaveDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 发布门槛入口（Q05）：只有"同一冻结内容 + 全样例终态 + 与候选同源 + 无阻断级失败 + 达门槛"的评测
 * 才能被记录成发布依据；其余一律以稳定错误码拒绝，绝不先记低分再靠预检兜底。
 */
class AiEvalPublishGateTest {

    private static final String DIGEST = "a".repeat(64);

    private static final String CONTENT_HASH = "h".repeat(64);

    private final AiEvalSuiteService suiteService = mock(AiEvalSuiteService.class);

    private final AiEvalRunService runService = mock(AiEvalRunService.class);

    private final AiRunMapper runMapper = mock(AiRunMapper.class);

    private final AiServiceReleaseMapper releaseMapper = mock(AiServiceReleaseMapper.class);

    private final AiServiceReleaseService releaseService = mock(AiServiceReleaseService.class);

    private final AiEvalPublishGate gate =
            new AiEvalPublishGate(suiteService, runService, runMapper, releaseMapper, releaseService);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    private static AiEvalSuiteDO suite() {
        return new AiEvalSuiteDO()
                .setId(7L)
                .setApplicationId(1L)
                .setCode("order-qa-eval")
                .setServiceId(4L)
                .setSubjectType("USER")
                .setDataLevel(AiEvalSuiteDO.LEVEL_INTERNAL)
                .setStatus(AiEvalSuiteDO.STATUS_FROZEN)
                .setRevision(2)
                .setContentDigest(DIGEST);
    }

    private static AiEvalRunDO run(String status, String suiteDigest) {
        AiEvalRunDO run = new AiEvalRunDO()
                .setApplicationId(1L)
                .setCaseTotal(2)
                .setId(3L)
                .setServiceId(4L)
                .setStatus(status)
                .setSuiteDigest(suiteDigest)
                .setSuiteId(7L)
                .setSuiteRevision(2);
        run.setStartedTime(java.time.LocalDateTime.now().minusMinutes(1));
        return run;
    }

    private static AiServiceReleaseDO release(int threshold) {
        return new AiServiceReleaseDO()
                .setId(9L)
                .setServiceId(4L)
                .setContentHash(CONTENT_HASH)
                .setEndpointConfigRevision(2)
                .setEvalThreshold(threshold);
    }

    private static AiEvalCaseDO evalCase(String caseKey, String severity) {
        return new AiEvalCaseDO()
                .setId(11L)
                .setSuiteId(7L)
                .setCaseKey(caseKey)
                .setTitle(caseKey)
                .setSeverity(severity)
                .setQuestion("合成问题")
                .setChecksJson("[{\"kind\":\"VALUE\",\"path\":\"status\",\"expected\":\"SUCCEEDED\"}]")
                .setNeedsReview(false)
                .setVersion(0);
    }

    private static AiEvalResultDO result(String caseKey, String severity, String status) {
        return new AiEvalResultDO()
                .setCaseDigest("b".repeat(64))
                .setCaseKey(caseKey)
                .setId(21L)
                .setResultDigest("c".repeat(64))
                .setReviewStatus(AiEvalResultDO.REVIEW_NOT_REQUIRED)
                .setRunId(3L)
                .setRunRef(55L)
                .setSeverity(severity)
                .setStatus(status);
    }

    @BeforeEach
    void setUp() {
        when(suiteService.requireSuite(7L)).thenReturn(suite());
        when(runService.requireRun(3L)).thenReturn(run(AiEvalRunDO.STATUS_COMPLETED, DIGEST));
        when(releaseMapper.selectById(9L)).thenReturn(release(0));
        AiRunDO executed = new AiRunDO()
                .setId(55L)
                .setApplicationId(1L)
                .setServiceId(4L)
                .setReleaseId(9L)
                .setContentHash(CONTENT_HASH)
                .setEndpointConfigRevision(2)
                .setRunKey("run_eval");
        when(runMapper.selectById(55L)).thenReturn(executed);
        when(suiteService.listCases(7L))
                .thenReturn(List.of(
                        evalCase("case_a", AiEvalCaseDO.SEVERITY_BLOCKER),
                        evalCase("case_b", AiEvalCaseDO.SEVERITY_MINOR)));
        when(runService.listResults(3L))
                .thenReturn(List.of(
                        result("case_a", AiEvalCaseDO.SEVERITY_BLOCKER, AiEvalResultDO.STATUS_PASSED),
                        result("case_b", AiEvalCaseDO.SEVERITY_MINOR, AiEvalResultDO.STATUS_PASSED)));
    }

    @Test
    void qualifiedEvaluationIsRecordedAsReleaseEvidence() {
        int score = gate.recordQualifiedEvaluation(7L, 3L, 9L);

        assertThat(score).isEqualTo(100);
        ArgumentCaptor<AiServiceEvaluationSaveDTO> captor = ArgumentCaptor.forClass(AiServiceEvaluationSaveDTO.class);
        verify(releaseService).recordEvaluation(captor.capture());
        assertThat(captor.getValue().getReleaseId()).isEqualTo(9L);
        assertThat(captor.getValue().getScore()).isEqualTo(100);
        assertThat(captor.getValue().getCaseCount()).as("全样例数（不是通过数）").isEqualTo(2);
        assertThat(captor.getValue().getNotes()).contains("order-qa-eval").contains("运行 3");
    }

    @Test
    void argumentAndOwnershipChecksComeFirst() {
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(null, 3L, 9L))
                .satisfies(exception -> assertCode(exception, AI_REQUEST_INVALID));
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(7L, null, 9L))
                .satisfies(exception -> assertCode(exception, AI_REQUEST_INVALID));
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(7L, 3L, null))
                .satisfies(exception -> assertCode(exception, AI_REQUEST_INVALID));

        when(releaseMapper.selectById(9L)).thenReturn(release(0).setServiceId(99L));
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(7L, 3L, 9L))
                .as("评测的是别的服务的发布：拒绝")
                .satisfies(exception -> assertCode(exception, AI_REQUEST_INVALID));
        verify(releaseService, never()).recordEvaluation(any());
    }

    @Test
    void unfinishedOrStaleEvaluationsAreRejectedWithSpecificCodes() {
        when(runService.requireRun(3L)).thenReturn(run(AiEvalRunDO.STATUS_RUNNING, DIGEST));
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(7L, 3L, 9L))
                .satisfies(exception -> assertCode(exception, AI_EVAL_PUBLISH_RUN_NOT_FINISHED));

        when(runService.requireRun(3L)).thenReturn(run(AiEvalRunDO.STATUS_COMPLETED, "b".repeat(64)));
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(7L, 3L, 9L))
                .as("套件在评测之后被改过：必须重新评测")
                .satisfies(exception -> assertCode(exception, AI_EVAL_PUBLISH_SUITE_CHANGED));

        when(runService.requireRun(3L)).thenReturn(run(AiEvalRunDO.STATUS_COMPLETED, DIGEST));
        when(runService.listResults(3L))
                .thenReturn(List.of(result("case_a", AiEvalCaseDO.SEVERITY_BLOCKER, AiEvalResultDO.STATUS_PASSED)));
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(7L, 3L, 9L))
                .as("只挑部分样例不能算覆盖")
                .satisfies(exception -> assertCode(exception, AI_EVAL_PUBLISH_CASE_COVERAGE_INCOMPLETE));
        verify(releaseService, never()).recordEvaluation(any());
    }

    @Test
    void unconvergedBlockersAndReleaseMismatchAreRejected() {
        when(runService.listResults(3L))
                .thenReturn(List.of(
                        result("case_a", AiEvalCaseDO.SEVERITY_BLOCKER, AiEvalResultDO.STATUS_ERROR),
                        result("case_b", AiEvalCaseDO.SEVERITY_MINOR, AiEvalResultDO.STATUS_PASSED)));
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(7L, 3L, 9L))
                .as("未能执行的样例不能算通过")
                .satisfies(exception -> assertCode(exception, AI_EVAL_PUBLISH_CASE_NOT_CONVERGED));

        when(runService.listResults(3L))
                .thenReturn(List.of(
                        result("case_a", AiEvalCaseDO.SEVERITY_BLOCKER, AiEvalResultDO.STATUS_PASSED),
                        result("case_b", AiEvalCaseDO.SEVERITY_MINOR, AiEvalResultDO.STATUS_REVIEW_REQUIRED)
                                .setReviewStatus(AiEvalResultDO.REVIEW_PENDING)));
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(7L, 3L, 9L))
                .as("等待人工复核的结果不能作为发布依据")
                .satisfies(exception -> assertCode(exception, AI_EVAL_PUBLISH_CASE_NOT_CONVERGED));

        when(runMapper.selectById(55L))
                .thenReturn(new AiRunDO()
                        .setId(55L)
                        .setReleaseId(9L)
                        .setContentHash("x".repeat(64))
                        .setEndpointConfigRevision(2));
        when(runService.listResults(3L))
                .thenReturn(List.of(
                        result("case_a", AiEvalCaseDO.SEVERITY_BLOCKER, AiEvalResultDO.STATUS_PASSED),
                        result("case_b", AiEvalCaseDO.SEVERITY_MINOR, AiEvalResultDO.STATUS_PASSED)));
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(7L, 3L, 9L))
                .as("内容摘要不同（提示词/数据集变了）必须重新评测")
                .satisfies(exception -> assertCode(exception, AI_EVAL_PUBLISH_RELEASE_MISMATCH));
    }

    @Test
    void blockerFailureAndLowScoreAreRejected() {
        when(runService.listResults(3L))
                .thenReturn(List.of(
                        result("case_a", AiEvalCaseDO.SEVERITY_BLOCKER, AiEvalResultDO.STATUS_FAILED),
                        result("case_b", AiEvalCaseDO.SEVERITY_MINOR, AiEvalResultDO.STATUS_PASSED)));
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(7L, 3L, 9L))
                .as("阻断级失败一票否决")
                .satisfies(exception -> assertCode(exception, AI_EVAL_PUBLISH_BLOCKER_CASE_FAILED));

        when(runService.listResults(3L))
                .thenReturn(List.of(
                        result("case_a", AiEvalCaseDO.SEVERITY_BLOCKER, AiEvalResultDO.STATUS_PASSED),
                        result("case_b", AiEvalCaseDO.SEVERITY_MINOR, AiEvalResultDO.STATUS_FAILED)));
        when(releaseMapper.selectById(9L)).thenReturn(release(80));
        assertThatThrownBy(() -> gate.recordQualifiedEvaluation(7L, 3L, 9L))
                .as("50% 低于门槛 80：直接拒绝记录，不写低分")
                .satisfies(exception -> assertCode(exception, AI_EVAL_PUBLISH_BELOW_THRESHOLD));
        verify(releaseService, never()).recordEvaluation(any());
    }
}
