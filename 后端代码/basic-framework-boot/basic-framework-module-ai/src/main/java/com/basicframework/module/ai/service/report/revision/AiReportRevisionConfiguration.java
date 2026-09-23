package com.basicframework.module.ai.service.report.revision;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 报表修订装配（R05）：补丁器是无状态纯逻辑，装配成单例供修订服务注入。
 *
 * <p>与 R03 的生成装配同一取舍：纯逻辑类不依赖 Spring（便于单测直接构造），装配放在配置类里。
 * 修订模型（{@link AiReportRevisionModel}）**不在这里装配**：真实模型调用由 M05 的调用服务实现，
 * 未装配时修订按稳定原因码 {@code AI_REPORT_REVISION_MODEL_UNAVAILABLE} 失败（fail-closed）。
 */
@Configuration(proxyBeanMethods = false)
public class AiReportRevisionConfiguration {

    /** 规格补丁器（确定性落结构，不调用模型与网络）。 */
    @Bean
    public AiReportSpecPatcher aiReportSpecPatcher() {
        return new AiReportSpecPatcher();
    }
}
