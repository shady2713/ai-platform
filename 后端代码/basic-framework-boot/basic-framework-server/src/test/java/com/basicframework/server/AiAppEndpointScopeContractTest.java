package com.basicframework.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import jakarta.annotation.security.PermitAll;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * A05 应用端端点契约：AI 应用端（{@code controller.app}）的每个映射必须显式声明策略，
 * 守卫表达式只能使用 scope 目录中的资源类型与动作，匿名端点必须已审查登记。
 *
 * <p>该扫描与 {@code EndpointAuthorizationContractTest} 互补：后者要求"必须且只能有一种策略"，
 * 前者进一步约束 AI 应用端的**策略内容**（scope 词表与匿名白名单）。
 */
class AiAppEndpointScopeContractTest {

    /** 已审查的匿名端点：与 docs/contracts/ai/scope-catalog.md 的登记表一一对应。 */
    private static final Set<String> REVIEWED_ANONYMOUS_ENDPOINTS =
            Set.of("com.basicframework.module.ai.controller.app.v1.auth.AiAuthController#issueTicket");

    /**
     * 已审查的"登录即可访问"端点：不声明 scope 表达式，归属判定在服务层按业务归属执行
     * （例如业务文件按 ai_file_binding 的所有者/授权目录判定），与 scope 目录同为登记制。
     */
    private static final Set<String> REVIEWED_AUTHENTICATED_ENDPOINTS = Set.of(
            "com.basicframework.module.ai.controller.app.v1.file.AiFileController#upload",
            "com.basicframework.module.ai.controller.app.v1.file.AiFileController#read",
            "com.basicframework.module.ai.controller.app.v1.file.AiFileController#release",
            // O01：会话与消息按 ai_conversation 归属判定（应用 + 主体 + 外部用户标识），与 scope 目录同为登记制
            "com.basicframework.module.ai.controller.app.v1.conversation.AiConversationController#create",
            "com.basicframework.module.ai.controller.app.v1.conversation.AiConversationController#page",
            "com.basicframework.module.ai.controller.app.v1.conversation.AiConversationController#get",
            "com.basicframework.module.ai.controller.app.v1.conversation.AiConversationController#rename",
            "com.basicframework.module.ai.controller.app.v1.conversation.AiConversationController#delete",
            "com.basicframework.module.ai.controller.app.v1.conversation.AiConversationController#bindService",
            "com.basicframework.module.ai.controller.app.v1.conversation.AiConversationController#bindRelease",
            "com.basicframework.module.ai.controller.app.v1.conversation.AiConversationController#appendMessage",
            "com.basicframework.module.ai.controller.app.v1.conversation.AiConversationController#messages",
            // O02：运行受理与查询按 ai_run 归属判定（应用 + 主体 + 外部用户标识）
            "com.basicframework.module.ai.controller.app.v1.run.AiRunController#accept",
            "com.basicframework.module.ai.controller.app.v1.run.AiRunController#get",
            "com.basicframework.module.ai.controller.app.v1.run.AiRunController#page",
            // O05：事件订阅与取消同样按运行归属判定（应用 + 主体 + 外部用户标识）
            "com.basicframework.module.ai.controller.app.v1.run.AiRunController#events",
            "com.basicframework.module.ai.controller.app.v1.run.AiRunController#cancel",
            // D09：工具动作按 ai_tool_action 归属判定（应用 + 主体类型 + 外部用户标识），确认走一次性挑战
            "com.basicframework.module.ai.controller.app.v1.action.AiToolActionController#confirm",
            "com.basicframework.module.ai.controller.app.v1.action.AiToolActionController#reject",
            "com.basicframework.module.ai.controller.app.v1.action.AiToolActionController#execute",
            "com.basicframework.module.ai.controller.app.v1.action.AiToolActionController#get",
            "com.basicframework.module.ai.controller.app.v1.action.AiToolActionController#page",
            // O06：任务进度查询与人工重试同样按运行归属判定
            "com.basicframework.module.ai.controller.app.v1.task.AiTaskController#progress",
            "com.basicframework.module.ai.controller.app.v1.task.AiTaskController#page",
            "com.basicframework.module.ai.controller.app.v1.task.AiTaskController#retry",
            // K06：知识检索与引用读取按当前主体的 A03 授权判定（过滤条件由服务端生成），
            // 片段/原文读取再次鉴权（A03 + A07 的业务文件权限 SPI）
            "com.basicframework.module.ai.controller.app.v1.knowledge.AiKnowledgeRetrievalController#search",
            "com.basicframework.module.ai.controller.app.v1.knowledge.AiKnowledgeRetrievalController#citation",
            "com.basicframework.module.ai.controller.app.v1.knowledge.AiKnowledgeRetrievalController#content",
            // R04：报表按 ai_report 归属判定（应用 + 主体类型 + 外部用户标识），
            // 读取（含旧版本编号）复核保存时的 A03 范围指纹，范围收窄后拒绝展示
            "com.basicframework.module.ai.controller.app.v1.report.AiReportController#save",
            "com.basicframework.module.ai.controller.app.v1.report.AiReportController#get",
            "com.basicframework.module.ai.controller.app.v1.report.AiReportController#page",
            "com.basicframework.module.ai.controller.app.v1.report.AiReportController#versions",
            "com.basicframework.module.ai.controller.app.v1.report.AiReportController#current",
            "com.basicframework.module.ai.controller.app.v1.report.AiReportController#version",
            // R05：报表对话修改同样按 ai_report 归属判定；展示类复用保存时的数据，数据类走受控查询，
            // 行范围来自授权层（请求体给不出行范围），成功只新增版本
            "com.basicframework.module.ai.controller.app.v1.report.AiReportController#revise");

    private static final String AI_APP_PACKAGE = "com.basicframework.module.ai.controller.app";

    /** @aiScope.hasScope('TYPE','key','ACTION') / hasAnyScope('TYPE','key','ACT1','ACT2') */
    private static final Pattern SCOPE_EXPRESSION =
            Pattern.compile("@aiScope\\.has(?:Any)?Scope\\(\\s*'([A-Z_]+)'\\s*,\\s*'([^']*)'\\s*,\\s*([^)]*)\\)");

    @Test
    void everyAiAppEndpointDeclaresExactlyOneReviewedPolicy() {
        List<String> violations = new ArrayList<>();
        for (Method method : aiAppControllerMethods()) {
            Set<String> policies = new TreeSet<>();
            if (AnnotatedElementUtils.hasAnnotation(method, PermitAll.class)) {
                policies.add("permitAll");
            }
            if (AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)) {
                policies.add("preAuthorize");
            }
            if (policies.isEmpty()) {
                violations.add(key(method) + " 缺少授权策略（@PermitAll 或 @PreAuthorize）");
            } else if (policies.size() > 1) {
                violations.add(key(method) + " 同时声明了多种策略： " + policies);
            }
            if (AnnotatedElementUtils.hasAnnotation(method, PermitAll.class)
                    && !REVIEWED_ANONYMOUS_ENDPOINTS.contains(key(method))) {
                violations.add(key(method) + " 是匿名端点但未在 scope 目录登记");
            }
            if (AnnotatedElementUtils.hasAnnotation(method, AuthenticatedOnly.class)
                    && !REVIEWED_AUTHENTICATED_ENDPOINTS.contains(key(method))) {
                violations.add(key(method) + " 只要求登录（服务层归属判定）但未在 scope 目录登记");
            }
        }
        assertThat(violations).as("AI 应用端端点策略必须完整且经审查").isEmpty();
    }

    @Test
    void scopeExpressionsUseCatalogVocabularyOnly() {
        List<String> violations = new ArrayList<>();
        Set<String> resourceTypes = new TreeSet<>();
        for (AiResourceType type : AiResourceType.values()) {
            resourceTypes.add(type.name());
        }
        Set<String> actions = new TreeSet<>();
        for (AiAction action : AiAction.values()) {
            actions.add(action.name());
        }
        for (Method method : aiAppControllerMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(method, AuthenticatedOnly.class)) {
                // 登录即可访问：不走 scope 表达式，归属判定由服务层按业务归属执行（已在目录登记）
                continue;
            }
            PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
            if (annotation == null) {
                continue;
            }
            Matcher matcher = SCOPE_EXPRESSION.matcher(annotation.value());
            if (!matcher.find()) {
                violations.add(key(method) + " 的守卫表达式未使用 @aiScope 与 scope 目录： " + annotation.value());
                continue;
            }
            if (!resourceTypes.contains(matcher.group(1))) {
                violations.add(key(method) + " 使用了目录外的资源类型： " + matcher.group(1));
            }
            for (String literal : quotedLiterals(matcher.group(3))) {
                if (!actions.contains(literal)) {
                    violations.add(key(method) + " 使用了目录外的动作： " + literal);
                }
            }
        }
        assertThat(violations).as("守卫表达式只能使用 scope 目录词表").isEmpty();
    }

    @Test
    void scopeCatalogDocumentListsExactlyTheEnumVocabulary() throws IOException {
        Path catalog = Path.of("..", "..", "..", "docs", "contracts", "ai", "scope-catalog.md");
        assertThat(Files.exists(catalog))
                .as("scope 目录文档必须存在：%s", catalog.toAbsolutePath())
                .isTrue();
        String content = Files.readString(catalog);
        for (AiResourceType type : AiResourceType.values()) {
            assertThat(content).as("scope 目录必须登记资源类型 %s", type).contains("`" + type.name() + "`");
        }
        for (AiAction action : AiAction.values()) {
            assertThat(content).as("scope 目录必须登记动作 %s", action).contains("`" + action.name() + "`");
        }
        for (String endpoint : REVIEWED_ANONYMOUS_ENDPOINTS) {
            String simpleName = endpoint.substring(endpoint.lastIndexOf('.') + 1);
            assertThat(content).as("scope 目录必须登记匿名端点 %s", simpleName).contains(simpleName);
        }
        for (String endpoint : REVIEWED_AUTHENTICATED_ENDPOINTS) {
            String simpleName = endpoint.substring(endpoint.lastIndexOf('.') + 1);
            assertThat(content).as("scope 目录必须登记登录即可访问端点 %s", simpleName).contains(simpleName);
        }
    }

    private static List<Method> aiAppControllerMethods() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Method> methods = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(AI_APP_PACKAGE)) {
            Class<?> controller = loadClass(definition.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                if (hasMappingAnnotation(method)) {
                    methods.add(method);
                }
            }
        }
        assertThat(methods).as("必须扫描到 AI 应用端端点（否则本契约形同虚设）").isNotEmpty();
        return methods;
    }

    private static boolean hasMappingAnnotation(Method method) {
        for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.startsWith("org.springframework.web.bind.annotation.") && (name.endsWith("Mapping"))) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("无法加载控制器：" + className, exception);
        }
    }

    private static List<String> quotedLiterals(String expression) {
        List<String> literals = new ArrayList<>();
        Matcher matcher = Pattern.compile("'([^']*)'").matcher(expression);
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }

    private static String key(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }
}
