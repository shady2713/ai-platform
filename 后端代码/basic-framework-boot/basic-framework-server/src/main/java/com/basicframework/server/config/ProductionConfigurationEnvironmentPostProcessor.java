package com.basicframework.server.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * 生产环境关键配置校验。
 */
public class ProductionConfigurationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROD_PROFILE = "prod";
    private static final String DEFAULT_CORS_ORIGIN = "https://admin.example.com";
    private static final Set<String> UNSAFE_DEFAULT_VALUES = Set.of(
            "admin",
            "admin123",
            "change_me",
            "changeme",
            "default",
            "password",
            "please_change_me",
            "root",
            "test",
            "123456");

    private static final List<String> REQUIRED_PROD_PROPERTIES = List.of(
            "spring.datasource.dynamic.datasource.master.url",
            "spring.datasource.dynamic.datasource.master.username",
            "spring.datasource.dynamic.datasource.master.password",
            "spring.flyway.url",
            "spring.flyway.user",
            "spring.flyway.password",
            "spring.data.redis.host",
            "spring.data.redis.password");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!isProdProfile(environment)) {
            return;
        }
        List<String> errors = new ArrayList<>();
        validateRequiredProperties(environment, errors);
        validateSeparateDatabasePrincipals(environment, errors);
        validateCorsOrigins(environment, errors);
        validateRefreshCookie(environment, errors);
        validateCredentialEncryptionKey(environment, errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("生产环境配置不安全或不完整：" + String.join("；", errors));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static boolean isProdProfile(ConfigurableEnvironment environment) {
        return Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
    }

    private static void validateRequiredProperties(ConfigurableEnvironment environment, List<String> errors) {
        for (String property : REQUIRED_PROD_PROPERTIES) {
            String value = environment.getProperty(property);
            if (isUnsafeValue(value) || isUnsafeDefaultValue(value)) {
                errors.add(property + " 必须通过生产环境配置注入，不能使用空值、占位符或弱默认值");
            }
        }
    }

    private static void validateSeparateDatabasePrincipals(ConfigurableEnvironment environment, List<String> errors) {
        String applicationUser = environment.getProperty("spring.datasource.dynamic.datasource.master.username");
        String migrationUser = environment.getProperty("spring.flyway.user");
        if (StringUtils.hasText(applicationUser)
                && StringUtils.hasText(migrationUser)
                && applicationUser.equalsIgnoreCase(migrationUser)) {
            errors.add("spring.flyway.user 必须与应用数据源账号分离，避免运行时持有 DDL 权限");
        }
    }

    private static void validateCorsOrigins(ConfigurableEnvironment environment, List<String> errors) {
        List<String> origins = Binder.get(environment)
                .bind("basic-framework.web.cors-allowed-origins", Bindable.listOf(String.class))
                .orElseGet(List::of);
        if (origins.isEmpty()) {
            errors.add("basic-framework.web.cors-allowed-origins 必须配置为生产域名");
            return;
        }
        for (int index = 0; index < origins.size(); index++) {
            String origin = origins.get(index);
            if (isUnsafeValue(origin)) {
                errors.add("basic-framework.web.cors-allowed-origins[" + index + "] 必须配置为生产域名");
            } else if (!isSafeCorsOrigin(origin)) {
                errors.add("basic-framework.web.cors-allowed-origins["
                        + index
                        + "] 必须是精确 HTTPS Origin，不能使用通配符、示例域名、本机地址或路径");
            }
        }
    }

    private static boolean isSafeCorsOrigin(String origin) {
        if (!origin.equals(origin.trim())
                || origin.contains("*")
                || origin.contains("?")
                || DEFAULT_CORS_ORIGIN.equalsIgnoreCase(origin)) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && StringUtils.hasText(host)
                    && uri.getUserInfo() == null
                    && !StringUtils.hasText(uri.getPath())
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && !isLocalCorsHost(host);
        } catch (RuntimeException invalidOrigin) {
            return false;
        }
    }

    private static boolean isLocalCorsHost(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return normalizedHost.equals("localhost")
                || normalizedHost.equals("localhost.")
                || normalizedHost.endsWith(".localhost")
                || normalizedHost.equals("0.0.0.0")
                || normalizedHost.startsWith("127.")
                || normalizedHost.equals("::1")
                || normalizedHost.equals("[::1]");
    }

    private static void validateRefreshCookie(ConfigurableEnvironment environment, List<String> errors) {
        boolean secure =
                environment.getProperty("basic-framework.security.refresh-cookie.secure", Boolean.class, false);
        if (!secure) {
            errors.add("basic-framework.security.refresh-cookie.secure 在生产环境必须启用");
        }
    }

    private static void validateCredentialEncryptionKey(ConfigurableEnvironment environment, List<String> errors) {
        String property = "basic-framework.security.credential-encryption-key";
        String encodedKey = environment.getProperty(property);
        if (isUnsafeValue(encodedKey) || isUnsafeDefaultValue(encodedKey)) {
            errors.add(property + " 必须通过生产环境配置注入");
            return;
        }
        try {
            if (Base64.getDecoder().decode(encodedKey).length != 32) {
                errors.add(property + " 必须是 32 字节 Base64 值");
            }
        } catch (IllegalArgumentException exception) {
            errors.add(property + " 必须是合法 Base64 值");
        }
    }

    private static boolean isUnsafeValue(String value) {
        return !StringUtils.hasText(value) || value.contains("${");
    }

    private static boolean isUnsafeDefaultValue(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
        return UNSAFE_DEFAULT_VALUES.contains(normalizedValue);
    }
}
