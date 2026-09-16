package com.basicframework.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import jakarta.annotation.security.PermitAll;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class EndpointAuthorizationContractTest {

    private static final String MODULE_BASE_PACKAGE = "com.basicframework.module";

    @Test
    void everyEndpointShouldDeclareItsAuthorizationPolicy() throws ClassNotFoundException {
        List<String> violations = new ArrayList<>();
        for (Class<?> controllerClass : findControllerClasses()) {
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (isEndpoint(method)) {
                    Set<String> policies = explicitPolicies(controllerClass, method);
                    if (policies.size() != 1) {
                        violations.add(controllerClass.getName() + "#" + method.getName() + " policies=" + policies);
                    }
                }
            }
        }

        violations.sort(Comparator.naturalOrder());
        assertThat(violations)
                .as("接口必须且只能声明 @PermitAll、@AuthenticatedOnly 或 @PreAuthorize 中的一种策略")
                .isEmpty();
    }

    private static List<Class<?>> findControllerClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        List<Class<?>> classes = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(MODULE_BASE_PACKAGE)) {
            String className = Objects.requireNonNull(candidate.getBeanClassName());
            classes.add(Class.forName(className));
        }
        return classes;
    }

    private static boolean isEndpoint(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class);
    }

    private static Set<String> explicitPolicies(Class<?> controllerClass, Method method) {
        Set<String> policies = new TreeSet<>();
        addPolicies(controllerClass, policies);
        addPolicies(method, policies);
        return policies;
    }

    private static void addPolicies(java.lang.reflect.AnnotatedElement element, Set<String> policies) {
        if (element.isAnnotationPresent(PermitAll.class)) {
            policies.add(PermitAll.class.getSimpleName());
        }
        if (element.isAnnotationPresent(AuthenticatedOnly.class)) {
            policies.add(AuthenticatedOnly.class.getSimpleName());
        }
        if (element.isAnnotationPresent(PreAuthorize.class)) {
            policies.add(PreAuthorize.class.getSimpleName());
        }
    }
}
