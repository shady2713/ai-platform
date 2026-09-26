package com.basicframework.module.ai.service.publish;

import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalResultDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalRunDO;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 发布前评测门槛的判据（Q05）：**纯函数**，输入是冻结的评测事实与发布快照，输出是通过与否、分数与阻断原因。
 *
 * <p>四条硬规则（都来自卡片验收项）：
 *
 * <ol>
 *   <li><b>不能只挑通过样例</b>：结果条数必须等于冻结样例数，且每条都是终态（无 ERROR / 待复核）；</li>
 *   <li><b>套件改过就要重评</b>：运行冻结的套件摘要必须与当前冻结摘要一致；</li>
 *   <li><b>换模型/提示词/数据集必须重新评测</b>：每个用例实际执行的运行，其发布版本、内容摘要与端点修订
 *       必须与待发布的候选完全一致；</li>
 *   <li><b>阻断级失败一票否决</b>：任何 BLOCKER 用例失败即拒绝发布。</li>
 * </ol>
 *
 * <p>分数只作为"记录到发布评估里的通过率"（四舍五入到整数），阈值判定由发布链路（S02 的预检）执行；
 * 本策略另外把"低于阈值"也列为阻断原因，便于调用方给出明确错误而不是先写低分再被拒。
 */
public final class AiEvalPublishPolicy {

    private AiEvalPublishPolicy() {}

    /** 一个用例的发布资格证据（实际执行的运行刻度）。 */
    public record CaseEvidence(
            String caseKey,
            String severity,
            String status,
            String reviewStatus,
            Long runRef,
            Long runReleaseId,
            String runContentHash,
            Integer runEndpointConfigRevision) {}

    /** 候选发布的冻结事实（编号 + 内容摘要 + 端点修订 + 门槛分）。 */
    public record ReleaseEvidence(
            Long releaseId, String contentHash, Integer endpointConfigRevision, Integer threshold) {}

    /** 判定输入。 */
    public record Evidence(
            String runStatus,
            String runSuiteDigest,
            String frozenSuiteDigest,
            int frozenCaseCount,
            List<CaseEvidence> cases,
            ReleaseEvidence release) {}

    /** 判定结果：是否可记录、分数、阻断原因（稳定词，按严重度排序）。 */
    public record Verdict(boolean qualified, int score, List<String> blockers) {}

    /** 阻断原因词表（与错误码一一对应，便于测试与报告）。 */
    public static final String BLOCKER_RUN_NOT_FINISHED = "RUN_NOT_FINISHED";

    public static final String BLOCKER_SUITE_CHANGED = "SUITE_CHANGED";

    public static final String BLOCKER_CASE_COVERAGE_INCOMPLETE = "CASE_COVERAGE_INCOMPLETE";

    public static final String BLOCKER_CASE_NOT_CONVERGED = "CASE_NOT_CONVERGED";

    public static final String BLOCKER_RELEASE_MISMATCH = "RELEASE_MISMATCH";

    public static final String BLOCKER_BLOCKER_CASE_FAILED = "BLOCKER_CASE_FAILED";

    public static final String BLOCKER_BELOW_THRESHOLD = "BELOW_THRESHOLD";

    public static Verdict evaluate(Evidence evidence) {
        List<String> blockers = new ArrayList<>();
        List<CaseEvidence> cases = evidence.cases() == null ? List.of() : evidence.cases();
        if (!AiEvalRunDO.STATUS_COMPLETED.equals(evidence.runStatus())) {
            blockers.add(BLOCKER_RUN_NOT_FINISHED);
        }
        if (!Objects.equals(evidence.frozenSuiteDigest(), evidence.runSuiteDigest())) {
            blockers.add(BLOCKER_SUITE_CHANGED);
        }
        if (cases.size() != evidence.frozenCaseCount() || evidence.frozenCaseCount() <= 0) {
            blockers.add(BLOCKER_CASE_COVERAGE_INCOMPLETE);
        }
        boolean converged = true;
        boolean blockerFailed = false;
        boolean pinsMatch = true;
        int passed = 0;
        for (CaseEvidence item : cases) {
            if (AiEvalResultDO.STATUS_PASSED.equals(item.status())) {
                passed++;
            } else if (!AiEvalResultDO.STATUS_FAILED.equals(item.status())) {
                // ERROR（未执行）与 REVIEW_REQUIRED（待复核）都不算数
                converged = false;
            }
            if (AiEvalCaseDO.SEVERITY_BLOCKER.equals(item.severity())
                    && !AiEvalResultDO.STATUS_PASSED.equals(item.status())) {
                blockerFailed = true;
            }
            if (!pinsMatch(item, evidence.release())) {
                pinsMatch = false;
            }
        }
        if (!converged) {
            blockers.add(BLOCKER_CASE_NOT_CONVERGED);
        }
        if (!pinsMatch) {
            blockers.add(BLOCKER_RELEASE_MISMATCH);
        }
        if (blockerFailed) {
            blockers.add(BLOCKER_BLOCKER_CASE_FAILED);
        }
        int total = cases.isEmpty() ? evidence.frozenCaseCount() : cases.size();
        int score = total <= 0 ? 0 : (int) Math.round(passed * 100.0 / total);
        int threshold = evidence.release() == null || evidence.release().threshold() == null
                ? 0
                : evidence.release().threshold();
        if (score < threshold) {
            blockers.add(BLOCKER_BELOW_THRESHOLD);
        }
        return new Verdict(blockers.isEmpty(), score, List.copyOf(blockers));
    }

    /** 用例实际执行的运行必须与候选发布完全同源（发布编号、内容摘要、端点修订）。 */
    private static boolean pinsMatch(CaseEvidence item, ReleaseEvidence release) {
        if (release == null || item.runRef() == null) {
            return false;
        }
        return Objects.equals(item.runReleaseId(), release.releaseId())
                && item.runContentHash() != null
                && item.runContentHash().equals(release.contentHash())
                && Objects.equals(item.runEndpointConfigRevision(), release.endpointConfigRevision());
    }
}
