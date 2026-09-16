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
 * 才能在同一 classpath 下覆盖 framework（core）、module-system、module-infra 的全量主源码类。
 * importOptions 只导入 main classes，排除测试类自身。
 *
 * 模块边界与授权缓存规则均为硬规则，任何新增违例直接让构建变红。
 * 规则 A/B/C 只豁免薄 api 模块明确发布的契约，调用方不得借包名触碰实现；
 * convert 包引用 VO 属本职（VO 与 DO 互转），规则 D 对其豁免。
 */
@AnalyzeClasses(packages = "com.basicframework..", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchitectureTest {

    private static final Set<String> PUBLISHED_API_CONTRACT_NAMES = Set.of(
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
}
