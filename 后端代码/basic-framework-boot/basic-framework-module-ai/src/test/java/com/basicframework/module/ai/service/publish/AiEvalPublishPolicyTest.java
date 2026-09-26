package com.basicframework.module.ai.service.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalResultDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalRunDO;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 发布门槛判据（Q05）：只挑部分样例、套件改过、换模型/提示词、阻断级失败都不允许通过。
 */
class AiEvalPublishPolicyTest {

    private static final String DIGEST = "a".repeat(64);

    private static final AiEvalPublishPolicy.ReleaseEvidence RELEASE =
            new AiEvalPublishPolicy.ReleaseEvidence(7L, "h".repeat(64), 2, 80);

    private static AiEvalPublishPolicy.CaseEvidence passed(String caseKey, String severity) {
        return new AiEvalPublishPolicy.CaseEvidence(
                caseKey,
                severity,
                AiEvalResultDO.STATUS_PASSED,
                AiEvalResultDO.REVIEW_NOT_REQUIRED,
                55L,
                7L,
                "h".repeat(64),
                2);
    }

    private static AiEvalPublishPolicy.CaseEvidence failed(String caseKey, String severity) {
        return new AiEvalPublishPolicy.CaseEvidence(
                caseKey,
                severity,
                AiEvalResultDO.STATUS_FAILED,
                AiEvalResultDO.REVIEW_NOT_REQUIRED,
                55L,
                7L,
                "h".repeat(64),
                2);
    }

    private static AiEvalPublishPolicy.Evidence evidence(List<AiEvalPublishPolicy.CaseEvidence> cases) {
        return new AiEvalPublishPolicy.Evidence(
                AiEvalRunDO.STATUS_COMPLETED, DIGEST, DIGEST, cases.size(), cases, RELEASE);
    }

    @Test
    void qualifiedRunScoresWholeSuiteAndPasses() {
        AiEvalPublishPolicy.Verdict verdict = AiEvalPublishPolicy.evaluate(evidence(List.of(
                passed("case_a", AiEvalCaseDO.SEVERITY_BLOCKER),
                passed("case_b", AiEvalCaseDO.SEVERITY_BLOCKER),
                failed("case_c", AiEvalCaseDO.SEVERITY_MINOR),
                passed("case_d", AiEvalCaseDO.SEVERITY_MINOR))));

        assertThat(verdict.score()).as("3/4 通过率 75%").isEqualTo(75);
        assertThat(verdict.qualified()).as("75% 低于门槛 80，不能记录成发布依据").isFalse();
        assertThat(verdict.blockers()).containsExactly(AiEvalPublishPolicy.BLOCKER_BELOW_THRESHOLD);

        AiEvalPublishPolicy.Verdict loweredThreshold = AiEvalPublishPolicy.evaluate(new AiEvalPublishPolicy.Evidence(
                AiEvalRunDO.STATUS_COMPLETED,
                DIGEST,
                DIGEST,
                4,
                List.of(
                        passed("case_a", AiEvalCaseDO.SEVERITY_BLOCKER),
                        passed("case_b", AiEvalCaseDO.SEVERITY_BLOCKER),
                        failed("case_c", AiEvalCaseDO.SEVERITY_MINOR),
                        passed("case_d", AiEvalCaseDO.SEVERITY_MINOR)),
                new AiEvalPublishPolicy.ReleaseEvidence(7L, "h".repeat(64), 2, 70)));
        assertThat(loweredThreshold.qualified()).as("门槛 70 时同样 75% 可以记录").isTrue();
        assertThat(loweredThreshold.blockers()).isEmpty();
    }

    @Test
    void cherryPickedOrPartialSuitesAreRejected() {
        // 冻结发布时是 4 例，运行里只有 3 例（挑掉了失败样例）
        AiEvalPublishPolicy.Evidence cherryPicked = new AiEvalPublishPolicy.Evidence(
                AiEvalRunDO.STATUS_COMPLETED,
                DIGEST,
                DIGEST,
                4,
                List.of(
                        passed("case_a", AiEvalCaseDO.SEVERITY_BLOCKER),
                        passed("case_b", AiEvalCaseDO.SEVERITY_MINOR),
                        passed("case_c", AiEvalCaseDO.SEVERITY_MINOR)),
                RELEASE);
        assertThat(AiEvalPublishPolicy.evaluate(cherryPicked).blockers())
                .contains(AiEvalPublishPolicy.BLOCKER_CASE_COVERAGE_INCOMPLETE);
    }

    @Test
    void suiteEditsAndModelChangesRequireReEvaluation() {
        AiEvalPublishPolicy.Evidence suiteChanged = new AiEvalPublishPolicy.Evidence(
                AiEvalRunDO.STATUS_COMPLETED,
                DIGEST,
                "b".repeat(64),
                1,
                List.of(passed("case_a", AiEvalCaseDO.SEVERITY_MINOR)),
                RELEASE);
        assertThat(AiEvalPublishPolicy.evaluate(suiteChanged).blockers())
                .contains(AiEvalPublishPolicy.BLOCKER_SUITE_CHANGED);

        AiEvalPublishPolicy.CaseEvidence otherModel = new AiEvalPublishPolicy.CaseEvidence(
                "case_a",
                AiEvalCaseDO.SEVERITY_MINOR,
                AiEvalResultDO.STATUS_PASSED,
                AiEvalResultDO.REVIEW_NOT_REQUIRED,
                55L,
                8L,
                "h".repeat(64),
                3);
        assertThat(AiEvalPublishPolicy.evaluate(evidence(List.of(otherModel))).blockers())
                .as("换了发布版本/端点修订：必须重新评测")
                .contains(AiEvalPublishPolicy.BLOCKER_RELEASE_MISMATCH);

        AiEvalPublishPolicy.CaseEvidence noRun = new AiEvalPublishPolicy.CaseEvidence(
                "case_a",
                AiEvalCaseDO.SEVERITY_MINOR,
                AiEvalResultDO.STATUS_PASSED,
                AiEvalResultDO.REVIEW_NOT_REQUIRED,
                null,
                null,
                null,
                null);
        assertThat(AiEvalPublishPolicy.evaluate(evidence(List.of(noRun))).blockers())
                .contains(AiEvalPublishPolicy.BLOCKER_RELEASE_MISMATCH);
    }

    @Test
    void blockerFailuresRunNotFinishedAndUnconvergedCasesBlock() {
        assertThat(AiEvalPublishPolicy.evaluate(evidence(List.of(failed("case_a", AiEvalCaseDO.SEVERITY_BLOCKER))))
                        .blockers())
                .contains(AiEvalPublishPolicy.BLOCKER_BLOCKER_CASE_FAILED);

        AiEvalPublishPolicy.Evidence running = new AiEvalPublishPolicy.Evidence(
                AiEvalRunDO.STATUS_RUNNING,
                DIGEST,
                DIGEST,
                1,
                List.of(passed("case_a", AiEvalCaseDO.SEVERITY_MINOR)),
                RELEASE);
        assertThat(AiEvalPublishPolicy.evaluate(running).blockers())
                .contains(AiEvalPublishPolicy.BLOCKER_RUN_NOT_FINISHED);

        AiEvalPublishPolicy.CaseEvidence error = new AiEvalPublishPolicy.CaseEvidence(
                "case_a",
                AiEvalCaseDO.SEVERITY_MINOR,
                AiEvalResultDO.STATUS_ERROR,
                AiEvalResultDO.REVIEW_NOT_REQUIRED,
                55L,
                7L,
                "h".repeat(64),
                2);
        assertThat(AiEvalPublishPolicy.evaluate(evidence(List.of(error))).blockers())
                .as("未执行（ERROR）不能算通过")
                .contains(AiEvalPublishPolicy.BLOCKER_CASE_NOT_CONVERGED);

        AiEvalPublishPolicy.CaseEvidence pending = new AiEvalPublishPolicy.CaseEvidence(
                "case_a",
                AiEvalCaseDO.SEVERITY_MINOR,
                AiEvalResultDO.STATUS_REVIEW_REQUIRED,
                AiEvalResultDO.REVIEW_PENDING,
                55L,
                7L,
                "h".repeat(64),
                2);
        assertThat(AiEvalPublishPolicy.evaluate(evidence(List.of(pending))).blockers())
                .as("等待人工复核的结果不能作为发布依据")
                .contains(AiEvalPublishPolicy.BLOCKER_CASE_NOT_CONVERGED);
    }

    @Test
    void emptyOrThresholdlessEvidenceIsHandledExplicitly() {
        AiEvalPublishPolicy.Evidence empty =
                new AiEvalPublishPolicy.Evidence(AiEvalRunDO.STATUS_COMPLETED, DIGEST, DIGEST, 0, List.of(), RELEASE);
        AiEvalPublishPolicy.Verdict verdict = AiEvalPublishPolicy.evaluate(empty);
        assertThat(verdict.qualified()).isFalse();
        assertThat(verdict.score()).isZero();
        assertThat(verdict.blockers()).contains(AiEvalPublishPolicy.BLOCKER_CASE_COVERAGE_INCOMPLETE);

        AiEvalPublishPolicy.Verdict noThreshold = AiEvalPublishPolicy.evaluate(new AiEvalPublishPolicy.Evidence(
                AiEvalRunDO.STATUS_COMPLETED,
                DIGEST,
                DIGEST,
                1,
                List.of(passed("case_a", AiEvalCaseDO.SEVERITY_MINOR)),
                new AiEvalPublishPolicy.ReleaseEvidence(7L, "h".repeat(64), 2, null)));
        assertThat(noThreshold.qualified()).isTrue();
        assertThat(noThreshold.score()).isEqualTo(100);
    }
}
