package com.basicframework.module.ai.service.evaluation;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_RESULT_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_RESULT_NOT_REVIEWABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_RUN_NOT_EXECUTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_RUN_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_SUITE_HAS_NO_CASE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalResultDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalRunDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.mysql.evaluation.AiEvalCaseMapper;
import com.basicframework.module.ai.dal.mysql.evaluation.AiEvalResultMapper;
import com.basicframework.module.ai.dal.mysql.evaluation.AiEvalRunMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.domain.runtime.AiRunBudget;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.service.run.AiRunExecutionService;
import com.basicframework.module.ai.service.run.AiRunService;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptDTO;
import com.basicframework.module.ai.service.run.dto.AiRunExecutionResultDTO;
import com.basicframework.module.ai.service.task.AiTaskService;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评测执行器实现（Q04）。
 *
 * <p>三个刻意的取舍：
 *
 * <ol>
 *   <li><b>评测身份来自服务端配置</b>：执行时用套件登记的应用 + 合成主体重建 MEMBER 会话
 *       （值来自库中配置，不接受请求参数），因此评测走的是与真实运行完全相同的授权链路；
 *       会话是临时的，执行前后恢复原有认证。</li>
 *   <li><b>不强占他人任务</b>：执行只领取**本次评测运行**的任务；领到别的任务时只把租约压到最短，
 *       交给恢复作业回收，绝不代跑（避免评测把线上队列带偏）。</li>
 *   <li><b>外部调用不在事务里</b>：一次评测是长流程，逐例各自短事务落库；失败按稳定错误码记为
 *       ERROR，不影响其它样例，也不留半成品结果。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiEvalRunServiceImpl implements AiEvalRunService {

    /** 领取任务的最大轮次（队列里有其它任务时适当重试）。 */
    private static final int CLAIM_ATTEMPTS = 8;

    /** 评测 worker 的租约秒数。 */
    private static final int LEASE_SECONDS = 60;

    /** 人工复核备注上限。 */
    private static final int REVIEW_NOTE_MAX = 512;

    private final AiEvalSuiteService suiteService;

    private final AiEvalCaseMapper caseMapper;

    private final AiEvalRunMapper runMapper;

    private final AiEvalResultMapper resultMapper;

    private final AiRunService runService;

    private final AiRunMapper aiRunMapper;

    private final AiTaskService taskService;

    private final AiRunExecutionService runExecutionService;

    @Override
    public Long startRun(Long suiteId) {
        AiEvalSuiteDO suite = suiteService.requireSuite(suiteId);
        List<AiEvalCaseDO> cases = caseMapper.selectBySuite(suiteId);
        if (cases.isEmpty()) {
            throw exception(AI_EVAL_SUITE_HAS_NO_CASE);
        }
        AiEvalRunDO run = new AiEvalRunDO()
                .setSuiteId(suiteId)
                .setApplicationId(suite.getApplicationId())
                .setServiceId(suite.getServiceId())
                .setSuiteRevision(suite.getRevision())
                .setSuiteDigest(AiEvalSuiteDigests.of(suite, cases))
                .setStatus(AiEvalRunDO.STATUS_RUNNING)
                .setCaseTotal(cases.size())
                .setPassedCount(0)
                .setFailedCount(0)
                .setErrorCount(0)
                .setSummaryJson(AiEvalSuiteDigests.summaryJson(cases))
                .setStartedTime(LocalDateTime.now());
        runMapper.insert(run);

        int passed = 0;
        int failed = 0;
        int errors = 0;
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        try {
            openEvalSession(suite);
            for (AiEvalCaseDO item : cases) {
                AiEvalResultDO result = executeCase(run, suite, item);
                resultMapper.insert(result);
                if (AiEvalResultDO.STATUS_PASSED.equals(result.getStatus())) {
                    passed++;
                } else if (AiEvalResultDO.STATUS_ERROR.equals(result.getStatus())) {
                    errors++;
                } else {
                    failed++;
                }
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
        runMapper.updateById(new AiEvalRunDO()
                .setId(run.getId())
                .setStatus(AiEvalRunDO.STATUS_COMPLETED)
                .setPassedCount(passed)
                .setFailedCount(failed)
                .setErrorCount(errors)
                .setFinishedTime(LocalDateTime.now()));
        return run.getId();
    }

    @Override
    public AiEvalRunDO requireRun(Long runId) {
        AiEvalRunDO run = runId == null ? null : runMapper.selectById(runId);
        if (run == null) {
            throw exception(AI_EVAL_RUN_NOT_EXISTS);
        }
        return run;
    }

    @Override
    public PageResult<AiEvalRunDO> pageRuns(PageParam pageParam, Long suiteId) {
        return runMapper.selectPage(pageParam, suiteId);
    }

    @Override
    public List<AiEvalResultDO> listResults(Long runId) {
        requireRun(runId);
        return resultMapper.selectByRun(runId);
    }

    @Override
    public PageResult<AiEvalResultDO> pageResults(PageParam pageParam, Long runId, String status) {
        requireRun(runId);
        if (status != null && !KNOWN_STATUSES.contains(status)) {
            throw exception(AI_REQUEST_INVALID);
        }
        return resultMapper.selectPage(pageParam, runId, status);
    }

    @Override
    public String report(Long runId) {
        AiEvalRunDO run = requireRun(runId);
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("caseTotal", run.getCaseTotal());
        head.put("errorCount", run.getErrorCount());
        head.put("failedCount", run.getFailedCount());
        head.put("finishedTime", run.getFinishedTime());
        head.put("passedCount", run.getPassedCount());
        head.put("runId", run.getId());
        head.put("serviceId", run.getServiceId());
        head.put("status", run.getStatus());
        head.put("suiteDigest", run.getSuiteDigest());
        head.put("suiteId", run.getSuiteId());
        head.put("suiteRevision", run.getSuiteRevision());
        report.put("run", head);
        List<Map<String, Object>> caseReports = new ArrayList<>();
        for (AiEvalResultDO result : resultMapper.selectByRun(runId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("caseDigest", result.getCaseDigest());
            item.put("caseKey", result.getCaseKey());
            item.put("expectVersion", result.getExpectVersion());
            item.put("failureCode", result.getFailureCode());
            item.put("observedVersion", result.getObservedVersion());
            item.put("resultDigest", result.getResultDigest());
            item.put("reviewNote", result.getReviewNote());
            item.put("reviewStatus", result.getReviewStatus());
            item.put("runRef", result.getRunRef());
            item.put("severity", result.getSeverity());
            item.put("status", result.getStatus());
            item.put("verdicts", AiEvalDigest.canonicalJsonText(result.getVerdictJson()));
            caseReports.add(item);
        }
        report.put("cases", caseReports);
        return JsonUtils.toJsonString(report);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void review(Long resultId, boolean approve, String note) {
        AiEvalResultDO result = requireResult(resultId);
        if (!AiEvalResultDO.REVIEW_PENDING.equals(result.getReviewStatus())) {
            throw exception(AI_EVAL_RESULT_NOT_REVIEWABLE);
        }
        String trimmed = note == null ? null : note.trim();
        if (trimmed != null && trimmed.length() > REVIEW_NOTE_MAX) {
            throw exception(AI_REQUEST_INVALID);
        }
        String reviewStatus = approve ? AiEvalResultDO.REVIEW_APPROVED : AiEvalResultDO.REVIEW_REJECTED;
        String status = approve ? AiEvalResultDO.STATUS_PASSED : AiEvalResultDO.STATUS_FAILED;
        Long reviewer = SecurityFrameworkUtils.getLoginUserId();
        resultMapper.updateById(new AiEvalResultDO()
                .setId(result.getId())
                .setReviewStatus(reviewStatus)
                .setReviewNote(trimmed)
                .setReviewedBy(reviewer == null ? "unknown" : String.valueOf(reviewer))
                .setReviewedTime(LocalDateTime.now())
                .setStatus(status)
                .setResultDigest(AiEvalDigest.digest(
                        result.getCaseDigest(),
                        AiEvalDigest.canonicalJsonText(result.getVerdictJson()),
                        status,
                        reviewStatus)));
    }

    @Override
    public AiEvalResultDO requireResult(Long resultId) {
        AiEvalResultDO result = resultId == null ? null : resultMapper.selectById(resultId);
        if (result == null) {
            throw exception(AI_EVAL_RESULT_NOT_EXISTS);
        }
        return result;
    }

    /** 单例执行：受理（同一运行服务）→ 领取本运行任务并执行 → 确定性核验。 */
    private AiEvalResultDO executeCase(AiEvalRunDO run, AiEvalSuiteDO suite, AiEvalCaseDO item) {
        AiEvalResultDO result = new AiEvalResultDO()
                .setRunId(run.getId())
                .setCaseId(item.getId())
                .setCaseKey(item.getCaseKey())
                .setSeverity(item.getSeverity())
                .setExpectVersion(item.getExpectVersion())
                .setReviewStatus(AiEvalResultDO.REVIEW_NOT_REQUIRED)
                .setCaseDigest(AiEvalSuiteDigests.caseDigest(item));
        String observed = null;
        String verdictsJson = null;
        try {
            Long runRef = runService
                    .accept(new AiRunAcceptDTO()
                            .setServiceId(suite.getServiceId())
                            .setIdempotencyKey("eval-" + run.getId() + "-" + item.getCaseKey())
                            .setMessage(item.getQuestion())
                            .setDataLevel(suite.getDataLevel()))
                    .getRunId();
            result.setRunRef(runRef);
            AiRunExecutionResultDTO executed = claimAndExecute(run.getId(), runRef);
            observed = observedJson(runRef, executed);
            result.setObservedVersion(observedVersion(runRef));
            List<AiEvalChecks.Verdict> verdicts = AiEvalChecks.verify(item.getChecksJson(), observed);
            verdictsJson = JsonUtils.toJsonString(verdicts);
            boolean needsReview = Boolean.TRUE.equals(item.getNeedsReview());
            boolean passed = AiEvalChecks.allPassed(verdicts);
            result.setVerdictJson(verdictsJson);
            result.setStatus(
                    passed
                            ? (needsReview ? AiEvalResultDO.STATUS_REVIEW_REQUIRED : AiEvalResultDO.STATUS_PASSED)
                            : AiEvalResultDO.STATUS_FAILED);
            result.setReviewStatus(needsReview ? AiEvalResultDO.REVIEW_PENDING : AiEvalResultDO.REVIEW_NOT_REQUIRED);
        } catch (ServiceException exception) {
            // 未执行成功：记稳定错误码，不编造判定
            result.setStatus(AiEvalResultDO.STATUS_ERROR).setFailureCode(String.valueOf(exception.getCode()));
        } catch (RuntimeException exception) {
            result.setStatus(AiEvalResultDO.STATUS_ERROR).setFailureCode(FAILURE_UNEXPECTED);
        }
        result.setResultDigest(AiEvalDigest.digest(
                result.getCaseDigest(),
                observed == null ? "" : observed,
                verdictsJson == null ? "" : verdictsJson,
                result.getStatus(),
                result.getFailureCode() == null ? "" : result.getFailureCode()));
        return result;
    }

    /** 领取本次评测运行的任务并执行；领到他任务时只缩短租约交给恢复作业。 */
    private AiRunExecutionResultDTO claimAndExecute(Long evalRunId, Long runRef) {
        String worker = "eval-worker-" + evalRunId;
        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            List<AiTaskLeaseDTO> leases = taskService.claim(worker, 1, LEASE_SECONDS);
            if (leases.isEmpty()) {
                break;
            }
            AiTaskLeaseDTO lease = leases.get(0);
            if (Objects.equals(lease.getRunId(), runRef)) {
                return runExecutionService.execute(lease, AiRunBudget.of(null, null, null));
            }
            taskService.heartbeat(lease, 1);
        }
        throw exception(AI_EVAL_RUN_NOT_EXECUTED);
    }

    /** 可观测事实：只放稳定值与摘要（提示词与响应正文不进报告）。 */
    private String observedJson(Long runRef, AiRunExecutionResultDTO executed) {
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("durationMillis", executed == null ? null : executed.getDurationMillis());
        observed.put(
                "outputDigest",
                executed == null || executed.getOutputText() == null
                        ? null
                        : AiEvalDigest.digest(executed.getOutputText()));
        observed.put("runId", runRef);
        observed.put("status", executed == null ? null : executed.getStatus());
        observed.put("toolCalls", executed == null ? null : executed.getToolCalls());
        observed.put("version", observedVersion(runRef));
        return JsonUtils.toJsonString(observed);
    }

    /** 实际版本标识：模型端点编号 + 受理时固定的配置修订。 */
    private String observedVersion(Long runRef) {
        AiRunDO run = runRef == null ? null : aiRunMapper.selectById(runRef);
        if (run == null || run.getModelEndpointId() == null) {
            return null;
        }
        return "endpoint:" + run.getModelEndpointId() + "@" + run.getEndpointConfigRevision();
    }

    /** 以套件登记的应用 + 合成主体打开临时 MEMBER 会话（值来自服务端配置）。 */
    private void openEvalSession(AiEvalSuiteDO suite) {
        Map<String, String> info = new LinkedHashMap<>();
        info.put(AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID, String.valueOf(suite.getApplicationId()));
        info.put(AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE, suite.getSubjectType());
        info.put(AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID, suite.getExternalUserId());
        LoginUser loginUser = new LoginUser()
                .setId(0L)
                .setUserType(UserTypeEnum.MEMBER.getValue())
                .setInfo(info);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private static final java.util.Set<String> KNOWN_STATUSES = java.util.Set.of(
            AiEvalResultDO.STATUS_PASSED,
            AiEvalResultDO.STATUS_FAILED,
            AiEvalResultDO.STATUS_ERROR,
            AiEvalResultDO.STATUS_REVIEW_REQUIRED);

    private static final String FAILURE_UNEXPECTED = "AI_EVAL_EXECUTION_FAILED";
}
