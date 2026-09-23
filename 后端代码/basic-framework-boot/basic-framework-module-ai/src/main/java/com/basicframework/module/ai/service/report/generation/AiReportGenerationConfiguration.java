package com.basicframework.module.ai.service.report.generation;

import com.basicframework.module.ai.service.report.validation.AiReportDataBinder;
import com.basicframework.module.ai.service.report.validation.AiReportSpecValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 报表生成装配（R03）：校验器与绑定器是无状态纯逻辑（R01），装配成单例供生成步骤注入。
 *
 * <p>与 K04/K05/K08 同一取舍：纯逻辑类不依赖 Spring（便于单测直接构造），装配放在配置类里。
 * 报表模型（{@link AiReportSpecModel}）**不在这里装配**：真实模型调用由 M04 的调用服务实现，
 * 未装配时生成步骤按稳定原因码 {@code AI_REPORT_MODEL_UNAVAILABLE} 失败（fail-closed）。
 */
@Configuration(proxyBeanMethods = false)
public class AiReportGenerationConfiguration {

    /** ReportSpec 校验器（R01）。 */
    @Bean
    public AiReportSpecValidator aiReportSpecValidator() {
        return new AiReportSpecValidator();
    }

    /** 报表数据绑定器（R01）。 */
    @Bean
    public AiReportDataBinder aiReportDataBinder() {
        return new AiReportDataBinder();
    }
}
