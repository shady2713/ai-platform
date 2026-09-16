package com.basicframework.framework.security.config;

import static com.basicframework.framework.common.util.collection.CollectionUtils.convertList;

import cn.hutool.core.collection.CollUtil;
import com.basicframework.framework.security.core.filter.TokenAuthenticationFilter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.DispatcherType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

/** Spring Security 的 fail-closed 配置：按公开规则、项目扩展规则、认证兜底的固定顺序装配过滤链。 */
@AutoConfiguration
@AutoConfigureOrder(-1) // 目的：先于 Spring Security 自动配置，避免一键改包后，org.* 基础包无法生效
@EnableMethodSecurity(securedEnabled = true)
public class BasicFrameworkWebSecurityConfigurerAdapter {

    /**
     * 认证失败处理类 Bean
     */
    private final AuthenticationEntryPoint authenticationEntryPoint;
    /**
     * 权限不够处理器 Bean
     */
    private final AccessDeniedHandler accessDeniedHandler;
    /**
     * Token 认证过滤器 Bean
     */
    private final TokenAuthenticationFilter authenticationTokenFilter;

    /**
     * 自定义的权限映射 Bean 们
     *
     * @see #filterChain(HttpSecurity)
     */
    private final List<AuthorizeRequestsCustomizer> authorizeRequestsCustomizers;

    public BasicFrameworkWebSecurityConfigurerAdapter(
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            TokenAuthenticationFilter authenticationTokenFilter,
            List<AuthorizeRequestsCustomizer> authorizeRequestsCustomizers) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.authenticationTokenFilter = authenticationTokenFilter;
        this.authorizeRequestsCustomizers = List.copyOf(authorizeRequestsCustomizers);
    }

    /**
     * 由于 Spring Security 创建 AuthenticationManager 对象时，没声明 @Bean 注解，导致无法被注入
     * 通过覆写父类的该方法，添加 @Bean 注解，解决该问题
     */
    @Bean
    public AuthenticationManager authenticationManagerBean(AuthenticationConfiguration authenticationConfiguration)
            throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    /**
     * 配置 URL 的安全配置
     *
     * anyRequest          |   匹配所有请求路径
     * access              |   SpringEl表达式结果为true时可以访问
     * anonymous           |   匿名可以访问
     * denyAll             |   用户不能访问
     * fullyAuthenticated  |   用户完全认证可以访问（非remember-me下自动登录）
     * hasAnyAuthority     |   如果有参数，参数表示权限，则其中任何一个权限可以访问
     * hasAnyRole          |   如果有参数，参数表示角色，则其中任何一个角色可以访问
     * hasAuthority        |   如果有参数，参数表示权限，则其权限可以访问
     * hasIpAddress        |   如果有参数，参数表示IP地址，如果用户IP和参数匹配，则可以访问
     * hasRole             |   如果有参数，参数表示角色，则其角色可以访问
     * permitAll           |   用户可以任意访问
     * rememberMe          |   允许通过remember-me登录的用户访问
     * authenticated       |   用户登录后可访问
     */
    @Bean
    protected SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
                .cors(Customizer.withDefaults())
                // access token 仅从显式 Header/请求参数读取；刷新令牌 Cookie 仅限认证端点，
                // 且由 AuthRefreshTokenCookieManager 校验浏览器 Origin。若引入 Cookie access token，
                // 必须在同一变更中恢复通用 CSRF 防护。
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(c -> c.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin))
                .exceptionHandling(c ->
                        c.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler));
        // 登录服务负责多用户、多登录方式的第一因子认证；过滤链负责后续令牌校验与授权。

        ApplicationContext applicationContext = httpSecurity.getSharedObject(ApplicationContext.class);
        Assert.state(applicationContext != null, "Spring Security 未提供 ApplicationContext 共享对象");
        Multimap<HttpMethod, String> permitAllUrls = getPermitAllUrlsFromAnnotations(applicationContext);
        httpSecurity
                // 全局公开规则必须先于项目扩展规则注册。
                .authorizeHttpRequests(c -> c.requestMatchers(HttpMethod.GET, "/*.html", "/*.css", "/*.js")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                permitAllUrls.get(HttpMethod.GET).toArray(new String[0]))
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                permitAllUrls.get(HttpMethod.POST).toArray(new String[0]))
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.PUT,
                                permitAllUrls.get(HttpMethod.PUT).toArray(new String[0]))
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.DELETE,
                                permitAllUrls.get(HttpMethod.DELETE).toArray(new String[0]))
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.HEAD,
                                permitAllUrls.get(HttpMethod.HEAD).toArray(new String[0]))
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.PATCH,
                                permitAllUrls.get(HttpMethod.PATCH).toArray(new String[0]))
                        .permitAll())
                // 项目只能通过扩展点追加规则，不能绕过最终认证兜底。
                .authorizeHttpRequests(c -> authorizeRequestsCustomizers.forEach(customizer -> customizer.customize(c)))
                .authorizeHttpRequests(c -> c.dispatcherTypeMatchers(DispatcherType.ASYNC)
                        // Servlet 异步重新分派沿用初始请求的安全上下文，允许 SSE 等异步响应完成。
                        .permitAll()
                        .anyRequest()
                        .authenticated());

        httpSecurity.addFilterBefore(authenticationTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return httpSecurity.build();
    }

    private Multimap<HttpMethod, String> getPermitAllUrlsFromAnnotations(ApplicationContext applicationContext) {
        Multimap<HttpMethod, String> result = HashMultimap.create();
        RequestMappingHandlerMapping requestMappingHandlerMapping =
                (RequestMappingHandlerMapping) applicationContext.getBean("requestMappingHandlerMapping");
        Map<RequestMappingInfo, HandlerMethod> handlerMethodMap = requestMappingHandlerMapping.getHandlerMethods();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethodMap.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            if (!handlerMethod.hasMethodAnnotation(PermitAll.class) // 方法级
                    && !handlerMethod.getBeanType().isAnnotationPresent(PermitAll.class)) { // 接口级
                continue;
            }
            Set<String> urls = new HashSet<>();
            if (entry.getKey().getPatternsCondition() != null) {
                urls.addAll(entry.getKey().getPatternsCondition().getPatterns());
            }
            if (entry.getKey().getPathPatternsCondition() != null) {
                urls.addAll(convertList(
                        entry.getKey().getPathPatternsCondition().getPatterns(), PathPattern::getPatternString));
            }
            if (urls.isEmpty()) {
                continue;
            }

            // 特殊：使用 @RequestMapping 注解，并且未写 method 属性，此时认为都需要免登录
            Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
            if (CollUtil.isEmpty(methods)) {
                result.putAll(HttpMethod.GET, urls);
                result.putAll(HttpMethod.POST, urls);
                result.putAll(HttpMethod.PUT, urls);
                result.putAll(HttpMethod.DELETE, urls);
                result.putAll(HttpMethod.HEAD, urls);
                result.putAll(HttpMethod.PATCH, urls);
                continue;
            }
            entry.getKey().getMethodsCondition().getMethods().forEach(requestMethod -> {
                switch (requestMethod) {
                    case GET:
                        result.putAll(HttpMethod.GET, urls);
                        break;
                    case POST:
                        result.putAll(HttpMethod.POST, urls);
                        break;
                    case PUT:
                        result.putAll(HttpMethod.PUT, urls);
                        break;
                    case DELETE:
                        result.putAll(HttpMethod.DELETE, urls);
                        break;
                    case HEAD:
                        result.putAll(HttpMethod.HEAD, urls);
                        break;
                    case PATCH:
                        result.putAll(HttpMethod.PATCH, urls);
                        break;
                    default:
                        throw new IllegalStateException("@PermitAll 不支持请求方法 " + requestMethod);
                }
            });
        }
        return result;
    }
}
