package com.basicframework.server.embed;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 嵌入路径的头部策略（C05）。
 *
 * <p>为什么需要一条独立的过滤链：框架的全局策略是 `X-Frame-Options: SAMEORIGIN`，
 * 它让管理端不能被跨源嵌套（这是对的，必须保留）；而嵌入页**就是要**被宿主站点跨源嵌套。
 * 浏览器在同时看到 `X-Frame-Options` 与 `frame-ancestors` 时会优先用 `frame-ancestors`，
 * 但依赖这个优先级不够明确——所以这里只对 `/app-api/ai/v1/embed/**` 这一个前缀关闭该头，
 * 允许域改由**响应头的 CSP `frame-ancestors`** 精确表达（每个应用一份，见 {@code AiEmbedShellController}）。
 *
 * <p>三个边界：
 * <ul>
 *   <li><b>不全局放宽</b>：`securityMatcher` 之外的一切路径（含管理端 `/admin-api/**`）继续走框架过滤链，
 *       仍然带 SAMEORIGIN 与认证；</li>
 *   <li><b>不放宽其他头</b>：本条链只覆盖 frame-options，其余安全头由框架默认值继续提供
 *       （`nosniff`、`Referrer-Policy` 等由控制器按需补足）；</li>
 *   <li><b>不引入第二套认证</b>：链内 `permitAll` 仅针对公开启动壳与自托管资产，
 *       所有 AI 业务调用都落在其他路径上，继续要求票据（scope 守卫或登录主体）。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AiEmbedSecurityConfiguration {

    /** 嵌入公开路径前缀（与控制器 `@RequestMapping` 一致）。 */
    public static final String EMBED_PATH_PATTERN = "/app-api/ai/v1/embed/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain aiEmbedSecurityFilterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
                .securityMatcher(EMBED_PATH_PATTERN)
                .cors(Customizer.withDefaults())
                // 与框架链同口径：无状态、无 Cookie 会话，故 CSRF 关闭；嵌入页不接受任何写入型请求
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(c -> c.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))
                .authorizeHttpRequests(c -> c.anyRequest().permitAll());
        return httpSecurity.build();
    }
}
