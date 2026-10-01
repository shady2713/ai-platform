package com.basicframework.module.ai.service.query.crosssource;

import com.basicframework.module.ai.service.authorization.crosssource.AiCrossSourceAuthorizationJudge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 跨源授权守卫的装配（Y07）。
 *
 * <p>{@link AiCrossSourceAuthorizationJudge} 是 Y05 交付的<b>纯判定器</b>：无状态、不持有任何
 * Bean 依赖，Y05 自己的验收用例直接 {@code new} 它使用。Y07 要在生产链路上复用它，
 * 就得让它成为一个可注入的 Bean。
 *
 * <p>这里用<b>装配类</b>而不是给 Y05 的类加 {@code @Component}：判定器是 Y05 的语义资产，
 * 它该不该进 Spring 容器属于本卡（Y07）的接线决定，不该写进 Y05 的源文件。
 * 好处是 Y05 的任何文件与测试都不需要改动，判定逻辑保持原样。
 */
@Configuration
public class CrossSourceMergeConfiguration {

    /**
     * Y05 跨源授权守卫（逐级求交 + 两条披露闸门）。
     *
     * <p>无状态，因此单例安全：判定输入全部来自方法参数。
     */
    @Bean
    public AiCrossSourceAuthorizationJudge crossSourceAuthorizationJudge() {
        return new AiCrossSourceAuthorizationJudge();
    }
}
