package com.basicframework.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

/**
 * 模块边界架构门禁
 *
 * 本测试放在 server 模块：它是唯一依赖全部业务模块的装配入口，
 * 才能在同一 classpath 下覆盖 framework（core）、module-system、module-infra、module-ai 的全量主源码类。
 * importOptions 只导入 main classes，排除测试类自身。
 *
 * 模块边界与授权缓存规则均为硬规则，任何新增违例直接让构建变红。
 * 规则 A/B/C/E/F/G 只豁免薄 api 模块明确发布的契约，调用方不得借包名触碰实现；
 * convert 包引用 VO 属本职（VO 与 DO 互转），规则 D/I 对其豁免；
 * 规则 H 的厂商类型边界只对 provider.springai 放行 Spring AI 类型。
 *
 * 拒绝证据：ModuleBoundaryArchitectureRejectionTest 用违例夹具证明每条规则都能变红。
 */
@AnalyzeClasses(packages = "com.basicframework..", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchitectureTest {

    private static final Set<String> PUBLISHED_API_CONTRACT_NAMES = Set.of(
            "com.basicframework.module.ai.api.run.AiRunCommonApi",
            "com.basicframework.module.ai.api.run.AiRunStatusEnum",
            "com.basicframework.module.infra.api.file.FileBusinessAccessProvider",
            "com.basicframework.module.infra.api.file.FileCommonApi",
            "com.basicframework.module.infra.api.file.dto.FileBusinessAccessContext",
            "com.basicframework.module.infra.api.file.dto.FileCreateReqDTO",
            "com.basicframework.module.infra.api.file.dto.FileDeleteReqDTO",
            "com.basicframework.module.infra.api.file.dto.FileReadReqDTO",
            "com.basicframework.module.infra.api.file.dto.FileRespDTO",
            "com.basicframework.module.infra.api.file.dto.FileSubjectDTO",
            "com.basicframework.module.infra.api.logger.ApiAccessLogCommonApi",
            "com.basicframework.module.infra.api.logger.ApiErrorLogCommonApi",
            "com.basicframework.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO",
            "com.basicframework.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO",
            "com.basicframework.module.system.api.dict.DictDataCommonApi",
            "com.basicframework.module.system.api.dict.dto.DictDataRespDTO",
            "com.basicframework.module.system.api.logger.OperateLogCommonApi",
            "com.basicframework.module.system.api.logger.dto.OperateLogCreateReqDTO",
            "com.basicframework.module.system.api.permission.PermissionCommonApi",
            "com.basicframework.module.system.api.permission.dto.DeptDataPermissionRespDTO",
            "com.basicframework.module.system.api.session.UserSessionCommonApi",
            "com.basicframework.module.system.api.session.dto.UserSessionCheckRespDTO");

    private static final DescribedPredicate<JavaClass> PUBLISHED_API_CONTRACT =
            new DescribedPredicate<>("published thin-module API contract") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return PUBLISHED_API_CONTRACT_NAMES.contains(javaClass.getName());
                }
            };

    /**
     * 规则 A：module-system 不得依赖 module-infra 的内部实现，只允许使用公开 api 契约。
     */
    @ArchTest
    static final ArchRule system_must_not_depend_on_infra = noClasses()
            .that()
            .resideInAPackage("com.basicframework.module.system..")
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.basicframework.module.infra..")
                    .and(DescribedPredicate.not(PUBLISHED_API_CONTRACT)));

    /**
     * 规则 B：module-infra 不得依赖 module-system 的内部实现，只允许使用公开 api 契约。
     */
    @ArchTest
    static final ArchRule infra_must_not_depend_on_system = noClasses()
            .that()
            .resideInAPackage("com.basicframework.module.infra..")
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.basicframework.module.system..")
                    .and(DescribedPredicate.not(PUBLISHED_API_CONTRACT)));

    /**
     * 规则 C：framework（core starters）不得依赖任何业务模块。
     * starter 是能力接缝，只允许被业务模块消费，反向依赖会破坏分层。
     * 豁免：仅允许 basic-framework-module-xxx-api 薄模块明确发布的
     * CommonApi + DTO，包名本身不构成豁免。
     */
    @ArchTest
    static final ArchRule framework_must_not_depend_on_modules = noClasses()
            .that()
            .resideInAPackage("com.basicframework.framework..")
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.basicframework.module..")
                    .and(DescribedPredicate.not(PUBLISHED_API_CONTRACT)));

    /**
     * 规则 D：service/dal 层不得依赖 controller 包（含 vo）。
     * controller 是入站适配层，service/dal 反向引用 VO 属于越层。
     */
    @ArchTest
    static final ArchRule service_dal_must_not_depend_on_controller = noClasses()
            .that()
            .resideInAnyPackage("..service..", "..dal..")
            .and()
            .resideOutsideOfPackage("..convert..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..controller..");

    /** 授权来源不得重新引入跨请求 Spring 缓存，防止撤销后在途回填恢复旧权限。 */
    @ArchTest
    static final ArchRule authorization_reads_must_not_use_spring_cache = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.basicframework.module.system.service.permission..",
                    "com.basicframework.module.system.service.dept..",
                    "com.basicframework.module.system.service.session..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.cache..");

    /**
     * 规则 E：module-ai 不得依赖 module-system 的内部实现，只允许使用公开 api 契约。
     */
    @ArchTest
    static final ArchRule ai_must_not_depend_on_system = noClasses()
            .that()
            .resideInAPackage("com.basicframework.module.ai..")
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.basicframework.module.system..")
                    .and(DescribedPredicate.not(PUBLISHED_API_CONTRACT)));

    /**
     * 规则 F：module-ai 不得依赖 module-infra 的内部实现，只允许使用公开 api 契约。
     */
    @ArchTest
    static final ArchRule ai_must_not_depend_on_infra = noClasses()
            .that()
            .resideInAPackage("com.basicframework.module.ai..")
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.basicframework.module.infra..")
                    .and(DescribedPredicate.not(PUBLISHED_API_CONTRACT)));

    /**
     * 规则 G：module-system / module-infra 不得依赖 module-ai 的内部实现，
     * 双向隔离避免既有业务模块反向耦合新模块；只允许消费 AI 薄契约。
     */
    @ArchTest
    static final ArchRule system_and_infra_must_not_depend_on_ai = noClasses()
            .that()
            .resideInAnyPackage("com.basicframework.module.system..", "com.basicframework.module.infra..")
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.basicframework.module.ai..")
                    .and(DescribedPredicate.not(PUBLISHED_API_CONTRACT)));

    /**
     * 规则 H：厂商类型边界。Spring AI 类型只能出现在 provider.springai 包内，
     * 其他类引用会把厂商契约泄漏进平台公开 API 与长期存储协议。
     */
    @ArchTest
    static final ArchRule vendor_types_must_stay_inside_provider_package = noClasses()
            .that()
            .resideInAPackage("com.basicframework..")
            .and()
            .resideOutsideOfPackage("com.basicframework.framework.ai.provider.springai..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.ai..");

    /**
     * 规则 I：service/dal 不得依赖平台自有的 VO 包（Controller 协议层类型）。
     * 与规则 D 同源但独立成立：VO 可以放在 controller 之外的包，越层同样拒绝；
     * convert 包负责 VO 与 DO 互转，属本职豁免。
     * 限定 com.basicframework 前缀：第三方库自带的 vo 包（如验证码组件的
     * com.anji.captcha.model.vo）不是本平台的协议层类型，不属于本规则范围。
     */
    @ArchTest
    static final ArchRule service_dal_must_not_depend_on_vo = noClasses()
            .that()
            .resideInAnyPackage("..service..", "..dal..")
            .and()
            .resideOutsideOfPackage("..convert..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.basicframework..vo..");
}
