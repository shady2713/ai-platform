package com.basicframework.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.controller.admin.application.AiApplicationController;
import com.basicframework.module.ai.controller.app.v1.auth.AiAuthController;
import com.basicframework.module.ai.controller.app.v1.file.AiFileController;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * A08 敏感端点登记：接触秘密/票据/凭据的 AI 端点必须登记在
 * {@code docs/security/ai-sensitive-endpoints.md}，且登记项必须真实存在。
 *
 * <p>与 scope 契约互补：后者管"谁能调用"，本测试管"敏感面是否被登记与审计"。
 */
class AiSensitiveEndpointRegistryTest {

    /** 敏感字段名模式：凭据、秘密、票据。 */
    private static final Pattern SENSITIVE_FIELD = Pattern.compile("(?i)(credential|secret|token)");

    private static final List<Class<?>> AI_CONTROLLERS =
            List.of(AiAuthController.class, AiApplicationController.class, AiFileController.class);

    private static final Path REGISTRY = Path.of("..", "..", "..", "docs", "security", "ai-sensitive-endpoints.md");

    @Test
    void everyEndpointTouchingCredentialOrTokenIsRegistered() throws IOException {
        assertThat(Files.exists(REGISTRY))
                .as("敏感端点登记文件必须存在：%s", REGISTRY.toAbsolutePath())
                .isTrue();
        String registry = Files.readString(REGISTRY);

        List<String> violations = new ArrayList<>();
        for (Class<?> controller : AI_CONTROLLERS) {
            String basePath = basePath(controller);
            for (Method method : controller.getDeclaredMethods()) {
                if (method.getAnnotation(RequestMapping.class) == null && !hasMappingAnnotation(method)) {
                    continue;
                }
                if (!touchesSensitiveData(method)) {
                    continue;
                }
                String endpointKey = endpointKey(controller, method);
                if (!registry.contains(endpointKey)) {
                    violations.add(endpointKey + "（" + basePath + "）未在敏感端点登记中列出");
                }
            }
        }
        assertThat(violations).as("敏感端点必须登记").isEmpty();
    }

    @Test
    void registryOnlyListsExistingEndpoints() throws IOException {
        String registry = Files.readString(REGISTRY);
        List<String> declared = new ArrayList<>();
        for (Class<?> controller : AI_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                declared.add(endpointKey(controller, method));
            }
        }
        // 登记表中出现的 `Controller#method` 标记必须能在控制器中找到
        var matcher = Pattern.compile("`([A-Za-z]+Controller#[A-Za-z]+)`").matcher(registry);
        while (matcher.find()) {
            String key = matcher.group(1);
            assertThat(declared).as("登记项 %s 必须对应真实端点", key).contains(key);
        }
    }

    @Test
    void aiEndpointsNeverAcceptTicketOrSecretAsPathOrQueryParameter() {
        List<String> violations = new ArrayList<>();
        for (Class<?> controller : AI_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                for (java.lang.reflect.Parameter parameter : method.getParameters()) {
                    String name = parameter.getName().toLowerCase(Locale.ROOT);
                    boolean sensitive = SENSITIVE_FIELD.matcher(name).find();
                    boolean pathOrQuery =
                            parameter.isAnnotationPresent(org.springframework.web.bind.annotation.PathVariable.class)
                                    || parameter.isAnnotationPresent(
                                            org.springframework.web.bind.annotation.RequestParam.class);
                    if (sensitive && pathOrQuery) {
                        violations.add(endpointKey(controller, method) + " 把敏感值放在路径/查询参数：" + name);
                    }
                }
            }
        }
        assertThat(violations).as("票据与秘密只能经请求头或请求体传递，不得出现在 URL").isEmpty();
    }

    private static boolean touchesSensitiveData(Method method) {
        String name = method.getName().toLowerCase(Locale.ROOT);
        if (SENSITIVE_FIELD.matcher(name).find()) {
            return true;
        }
        for (Class<?> parameterType : method.getParameterTypes()) {
            for (Field field : parameterType.getDeclaredFields()) {
                if (SENSITIVE_FIELD.matcher(field.getName()).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasMappingAnnotation(Method method) {
        for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
            String annotationName = annotation.annotationType().getName();
            if (annotationName.startsWith("org.springframework.web.bind.annotation.")
                    && annotationName.endsWith("Mapping")) {
                return true;
            }
        }
        return false;
    }

    private static String basePath(Class<?> controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        return mapping == null ? "" : String.join(",", mapping.value());
    }

    private static String endpointKey(Class<?> controller, Method method) {
        return controller.getSimpleName() + "#" + method.getName();
    }
}
