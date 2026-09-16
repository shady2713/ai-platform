package com.basicframework.module.infra.controller.admin.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basicframework.framework.web.config.BasicFrameworkWebAutoConfiguration;
import com.basicframework.module.infra.api.logger.ApiErrorLogCommonApi;
import com.basicframework.module.infra.service.config.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller 边界参数校验的 Web 层证据：类级 @Validated 激活方法参数约束后，
 * 非法编号与非法分页参数必须在到达 Service 之前以 400 拒绝。
 * 切片内不装载安全过滤器与自定义 AOP，只验证参数约束与全局异常映射（ADR 0003）；
 * 异常处理与 API 前缀由生产自动配置装配，禁止在测试中补造缺失的 advice。
 */
@WebMvcTest(controllers = ConfigController.class, properties = "spring.application.name=config-web-test")
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration({BasicFrameworkWebAutoConfiguration.class, RestTemplateAutoConfiguration.class})
@ContextConfiguration(classes = ConfigController.class)
class ConfigControllerValidationWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConfigService configService;

    @MockitoBean
    private ApiErrorLogCommonApi apiErrorLogCommonApi;

    @Test
    void getConfig_nonPositiveId_rejectedWithBadRequest() throws Exception {
        mockMvc.perform(get("/admin-api/infra/config/get").param("id", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void deleteConfig_negativeId_rejectedWithBadRequest() throws Exception {
        mockMvc.perform(delete("/admin-api/infra/config/delete").param("id", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void export_invalidPageSize_rejectedWithBadRequest() throws Exception {
        mockMvc.perform(get("/admin-api/infra/config/export-excel").param("pageSize", "0"))
                .andExpect(status().isBadRequest());
    }
}
