package com.basicframework.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * 模块边界门禁的拒绝测试。
 *
 * <p>根 AGENTS.md 要求"CI 强制的要求必须有阻断门禁与一个证明其能拒绝违规的测试"。
 * 本测试把 {@link ModuleBoundaryArchitectureTest} 中新增的 AI 边界、厂商类型边界与
 * VO 越层规则套用到测试夹具上，断言代表性违例确实被判定为违规，
 * 且合规写法（薄契约消费、provider 包内使用厂商类型）不被误伤。
 *
 * <p>夹具包位于 server 测试源码（{@code archfixture}），生产门禁以
 * {@code ImportOption.DoNotIncludeTests} 排除它们，因此夹具不会成为真实依赖。
 */
class ModuleBoundaryArchitectureRejectionTest {

    private static final JavaClasses AI_FIXTURES =
            new ClassFileImporter().importPackages("com.basicframework.module.ai.archfixture");

    private static final JavaClasses INFRA_FIXTURES =
            new ClassFileImporter().importPackages("com.basicframework.module.infra.archfixture");

    private static final JavaClasses VENDOR_PROVIDER_FIXTURES =
            new ClassFileImporter().importPackages("com.basicframework.framework.ai.provider.springai.archfixture");

    private static String details(EvaluationResult result) {
        return String.join("\n", result.getFailureReport().getDetails());
    }

    @Test
    void rule_e_rejects_ai_depending_on_system_internals() {
        EvaluationResult result = ModuleBoundaryArchitectureTest.ai_must_not_depend_on_system.evaluate(AI_FIXTURES);

        assertThat(result.hasViolation()).isTrue();
        assertThat(details(result)).contains("AiDependsOnSystemInternalsFixture");
    }

    @Test
    void rule_f_rejects_ai_depending_on_infra_internals() {
        EvaluationResult result = ModuleBoundaryArchitectureTest.ai_must_not_depend_on_infra.evaluate(AI_FIXTURES);

        assertThat(result.hasViolation()).isTrue();
        assertThat(details(result)).contains("AiDependsOnInfraInternalsFixture");
    }

    @Test
    void rule_g_rejects_module_depending_on_ai_internals_but_allows_published_contract() {
        EvaluationResult result =
                ModuleBoundaryArchitectureTest.system_and_infra_must_not_depend_on_ai.evaluate(INFRA_FIXTURES);

        assertThat(result.hasViolation()).isTrue();
        assertThat(details(result)).contains("InfraDependsOnAiInternalsFixture");
        assertThat(details(result)).doesNotContain("InfraUsingPublishedAiContractFixture");
    }

    @Test
    void rule_h_rejects_vendor_types_outside_provider_package() {
        EvaluationResult result =
                ModuleBoundaryArchitectureTest.vendor_types_must_stay_inside_provider_package.evaluate(AI_FIXTURES);

        assertThat(result.hasViolation()).isTrue();
        assertThat(details(result)).contains("VendorLeakFixture");
    }

    @Test
    void rule_h_allows_vendor_types_inside_provider_package() {
        EvaluationResult result = ModuleBoundaryArchitectureTest.vendor_types_must_stay_inside_provider_package
                .allowEmptyShould(true)
                .evaluate(VENDOR_PROVIDER_FIXTURES);

        assertThat(result.hasViolation()).isFalse();
    }

    @Test
    void rule_i_rejects_service_depending_on_vo() {
        EvaluationResult result =
                ModuleBoundaryArchitectureTest.service_dal_must_not_depend_on_vo.evaluate(AI_FIXTURES);

        assertThat(result.hasViolation()).isTrue();
        assertThat(details(result)).contains("VoLeakServiceFixture");
    }
}
