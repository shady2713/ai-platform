package com.basicframework.framework.web.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "basic-framework.web")
@Validated
@Data
public class WebProperties {

    public static final int MAX_REQUEST_BODY_CACHE_BYTES = 10 * 1024 * 1024;

    @NotNull(message = "APP API 不能为空")
    @Valid
    private Api appApi = new Api("/app-api", "**.controller.app.**");

    @NotNull(message = "Admin API 不能为空")
    @Valid
    private Api adminApi = new Api("/admin-api", "**.controller.admin.**");

    /**
     * CORS 允许的源地址列表。生产环境仅允许精确 HTTPS Origin，不允许 Ant 通配符、路径或本机地址。
     * 例如：https://admin.example.com
     */
    private List<String> corsAllowedOrigins = List.of("*");

    /**
     * 可信反向代理列表（IP 或 IPv4 CIDR）。仅当请求直接对端命中该列表时，才采信
     * X-Forwarded-For / X-Real-IP 推导客户端 IP；默认留空表示不信任任何代理头（fail-closed，
     * 防止伪造代理头绕过按 IP 的限流与审计）。部署在 Nginx/负载均衡之后时，必须配置为
     * 代理的出口 IP/网段，否则按 IP 的限流与审计会取到代理 IP。
     */
    private List<String> trustedProxies = List.of();

    /** 访问日志可重复读取的 JSON 请求体上限；文件上传不经过此缓存。 */
    @Min(value = 1, message = "JSON 请求体缓存上限必须大于 0")
    @Max(value = MAX_REQUEST_BODY_CACHE_BYTES, message = "JSON 请求体缓存上限不能超过 10MB")
    private int requestBodyCacheMaxBytes = 1024 * 1024;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Api {

        /**
         * API 前缀，实现所有 Controller 提供的 RESTFul API 的统一前缀
         *
         *
         * 意义：通过该前缀，避免 Swagger、Actuator 意外通过 Nginx 暴露出来给外部，带来安全性问题
         *      这样，Nginx 只需要配置转发到 /api/* 的所有接口即可。
         *
         * @see BasicFrameworkWebAutoConfiguration#webMvcRegistrations(WebProperties)
         */
        @NotEmpty(message = "API 前缀不能为空")
        @Pattern(regexp = "^/(?:[^/]+(?:/[^/]+)*)?$", message = "API 前缀必须以 / 开头且不能包含空路径段")
        private String prefix;

        /**
         * Controller 所在包的 Ant 路径规则
         *
         * 主要目的是，给该 Controller 设置指定的 {@link #prefix}
         */
        @NotEmpty(message = "Controller 所在包不能为空")
        private String controller;
    }
}
