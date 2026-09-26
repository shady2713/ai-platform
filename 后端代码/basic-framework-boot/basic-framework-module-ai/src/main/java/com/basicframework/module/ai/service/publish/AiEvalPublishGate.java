package com.basicframework.module.ai.service.publish;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_BELOW_THRESHOLD;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_BLOCKER_CASE_FAILED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_CASE_COVERAGE_INCOMPLETE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_CASE_NOT_CONVERGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_RELEASE_MISMATCH;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_RUN_NOT_FINISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_PUBLISH_SUITE_CHANGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.exception.ErrorCode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评测发布门槛（Q05）：把一次评测"换算成"发布评估记录的唯一合法入口。
 *
 * <p>为什么不直接让发布链路读评测表：发布链路（S02）的预检要求"最新评估记录的
 * {@code contentHash} 与 {@code endpointConfigRevision} 与候选发布一致，且 {@code passed} 且分数达门槛"。
 * 因此把**评测结果**写进评估记录的这一步就是发布门槛本体——只要这一步只认合格评测，
 * "未达到阈值/只挑通过样例/换模型不重评"这些规则就都由既有预检强制执行，不需要发布链路知道评测的存在。
 *
 * <p>判定规则与阻断词表见 {@link AiEvalPublishPolicy}；四个布尔事实（运行状态、套件摘要、样例覆盖、
 * 发布同源）都必须成立，任一阻断原因都会以对应错误码拒绝记录。
 *
 * <p>**已知缺口（记在 Q05 证据里）**：评测执行走的是"按当前已发布版本受理运行"的公开路径，
 * 因此本门槛能验证的是"与评测同源"的候选（内容摘要与端点修订完全一致的重新上线/回退）；
 * 内容有变化的候选会被判 {@code RELEASE_MISMATCH} 并要求重新评测，而针对**候选版本**产生新评测
 * 需要受理路径支持固定候选版本（属 O02/S02 的接缝，本卡未授权）。
 */
@Service
@RequiredArgsConstructor
public class AiEvalPublishGate {

    private final AiEvalSuiteService suiteService;

    private final AiEvalRunService runService;

    private final AiRunMapper runMapper;

    private final AiServiceReleaseMapper releaseMapper;

    private final AiServiceReleaseService releaseService;

    /**
     * 用一次评测运行给候选发布记录评估（发布门槛）。
     *
     * @return 记录到发布评估里的通过率（0-100）
     * @throws com.basicframework.framework.common.exception.ServiceException 评测不合格时按稳定错误码拒绝
     */
    @Transactional(rollbackFor = Exception.class)
    public int recordQualifiedEvaluation(Long suiteId, Long runId, Long releaseId) {
        if (suiteId == null || runId == null || releaseId == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiEvalSuiteDO suite = suiteService.requireSuite(suiteId);
        AiEvalRunDO run = runService.requireRun(runId);
        AiServiceReleaseDO release = releaseMapper.selectById(releaseId);
        if (release == null || !Objects.equals(release.getServiceId(), suite.getServiceId())) {
            // 评测的是另一个服务的发布：直接拒绝，避免用无关评测刷通过率
            throw exception(AI_REQUEST_INVALID);
        }
        List<AiEvalCaseDO> cases = suiteService.listCases(suiteId);
        List<AiEvalResultDO> results = runService.listResults(runId);

        AiEvalPublishPolicy.Evidence evidence = new AiEvalPublishPolicy.Evidence(
                run.getStatus(),
                run.getSuiteDigest(),
                suite.getContentDigest(),
                cases.size(),
                resultsToEvidence(results),
                new AiEvalPublishPolicy.ReleaseEvidence(
                        release.getId(),
                        release.getContentHash(),
                        release.getEndpointConfigRevision(),
                        release.getEvalThreshold()));
        AiEvalPublishPolicy.Verdict verdict = AiEvalPublishPolicy.evaluate(evidence);
        if (!verdict.qualified()) {
            throw exception(codeOf(verdict.blockers().get(0)));
        }
        releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(releaseId)
                .setScore(verdict.score())
                .setCaseCount(cases.size())
                .setNotes("评测套件 " + suite.getCode() + " 修订 " + suite.getRevision() + "，运行 " + run.getId()));
        return verdict.score();
    }

    /** 只读取数：把结果行与实际执行的运行运行刻度拼成判定证据。 */
    private List<AiEvalPublishPolicy.CaseEvidence> resultsToEvidence(List<AiEvalResultDO> results) {
        Map<Long, AiRunDO> runsById = new ArrayList<>(results)
                .stream()
                        .map(AiEvalResultDO::getRunRef)
                        .filter(Objects::nonNull)
                        .distinct()
                        .map(runMapper::selectById)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(AiRunDO::getId, Function.identity(), (first, second) -> first));
        List<AiEvalPublishPolicy.CaseEvidence> evidence = new ArrayList<>();
        for (AiEvalResultDO result : results) {
            AiRunDO executed = result.getRunRef() == null ? null : runsById.get(result.getRunRef());
            evidence.add(new AiEvalPublishPolicy.CaseEvidence(
                    result.getCaseKey(),
                    result.getSeverity(),
                    result.getStatus(),
                    result.getReviewStatus(),
                    result.getRunRef(),
                    executed == null ? null : executed.getReleaseId(),
                    executed == null ? null : executed.getContentHash(),
                    executed == null ? null : executed.getEndpointConfigRevision()));
        }
        return evidence;
    }

    private static ErrorCode codeOf(String blocker) {
        return switch (blocker) {
            case AiEvalPublishPolicy.BLOCKER_RUN_NOT_FINISHED -> AI_EVAL_PUBLISH_RUN_NOT_FINISHED;
            case AiEvalPublishPolicy.BLOCKER_SUITE_CHANGED -> AI_EVAL_PUBLISH_SUITE_CHANGED;
            case AiEvalPublishPolicy.BLOCKER_CASE_COVERAGE_INCOMPLETE -> AI_EVAL_PUBLISH_CASE_COVERAGE_INCOMPLETE;
            case AiEvalPublishPolicy.BLOCKER_CASE_NOT_CONVERGED -> AI_EVAL_PUBLISH_CASE_NOT_CONVERGED;
            case AiEvalPublishPolicy.BLOCKER_RELEASE_MISMATCH -> AI_EVAL_PUBLISH_RELEASE_MISMATCH;
            case AiEvalPublishPolicy.BLOCKER_BLOCKER_CASE_FAILED -> AI_EVAL_PUBLISH_BLOCKER_CASE_FAILED;
            case AiEvalPublishPolicy.BLOCKER_BELOW_THRESHOLD -> AI_EVAL_PUBLISH_BELOW_THRESHOLD;
            default -> AI_REQUEST_INVALID;
        };
    }
}
