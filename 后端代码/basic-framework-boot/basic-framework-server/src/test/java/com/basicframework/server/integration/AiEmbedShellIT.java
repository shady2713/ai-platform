package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.theme.AiThemeService;
import com.basicframework.module.ai.service.theme.dto.AiThemeSaveDTO;
import jakarta.servlet.Filter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * C05 嵌入页与安全头（AT-056，真实 MySQL + 真实安全过滤链）。
 *
 * <p>要点是**边界**而不是"能打开"：嵌入路径匿名可访问且只允许配置的精确 Origin 嵌套；
 * 同一进程内管理端与其他应用端路径**保持** `X-Frame-Options: SAMEORIGIN` 与认证要求；
 * 允许域缺失/非法、应用未知/停用、构建产物缺失都 fail-closed。
 */
@TestPropertySource(properties = "basic-framework.ai.embed.assets-directory=target/test-classes/embed-fixture")
class AiEmbedShellIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-c05-app";

    private static final String ORIGIN = "https://crm.example.com";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiThemeService themeService;

    private Long applicationId;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(webApplicationContext.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @AfterEach
    void cleanUp() {
        if (applicationId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM ai_theme WHERE application_id = ?", applicationId);
        jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", applicationId);
        jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", applicationId);
        applicationId = null;
    }

    private static String tokensJson() {
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("primaryColor", "#2563eb");
        tokens.put("radius", 4);
        tokens.put("fontFamily", "system-ui, -apple-system, \"PingFang SC\", \"Microsoft YaHei\", sans-serif");
        return JsonUtils.toJsonString(tokens);
    }

    private Long createApplication(String appCode, List<String> origins, boolean enabled) {
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(appCode)
                .setName("IT 嵌入应用")
                .setOrigins(origins));
        Long id = issue.getApplication().getId();
        applicationService.updateStatus(id, 0, enabled);
        return id;
    }

    @Test
    void embedShellIsPublicWithExactAncestorsWhileOtherPathsKeepFrameProtection() throws Exception {
        applicationId = createApplication(APP_CODE, List.of(ORIGIN), true);
        MockMvc mockMvc = mockMvc();

        MvcResult shell =
                mockMvc.perform(get("/app-api/ai/v1/embed/" + APP_CODE)).andReturn();
        assertThat(shell.getResponse().getStatus()).isEqualTo(200);
        assertThat(shell.getResponse().getContentType()).contains("text/html");
        assertThat(shell.getResponse().getContentAsString())
                .contains("<!doctype html>")
                .contains("entry-test.js");
        assertThat(shell.getResponse().getHeader("Content-Security-Policy"))
                .contains("frame-ancestors " + ORIGIN)
                .doesNotContain("unsafe-inline")
                .doesNotContain("unsafe-eval");
        assertThat(shell.getResponse().getHeader("Cache-Control")).contains("no-cache");
        // 只对嵌入路径关闭该头：跨源嵌套由上面的 frame-ancestors 精确控制
        assertThat(shell.getResponse().getHeader("X-Frame-Options")).isNull();

        // 管理端不受影响：仍然要求认证且仍带 SAMEORIGIN
        MvcResult admin = mockMvc.perform(get("/admin-api/ai/theme/page")).andReturn();
        assertThat(admin.getResponse().getStatus()).isEqualTo(401);
        assertThat(admin.getResponse().getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");

        // 其他应用端路径同样不受影响
        MvcResult appApi = mockMvc.perform(get("/app-api/ai/conversation/page")).andReturn();
        assertThat(appApi.getResponse().getStatus()).isEqualTo(401);
        assertThat(appApi.getResponse().getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
    }

    @Test
    void unknownDisabledAndUnusableApplicationsFailClosed() throws Exception {
        applicationId = createApplication(APP_CODE, List.of(ORIGIN), false);
        MockMvc mockMvc = mockMvc();

        // 停用应用：404（与不存在同语义）
        assertThat(mockMvc.perform(get("/app-api/ai/v1/embed/" + APP_CODE))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        // 未知应用：404
        assertThat(mockMvc.perform(get("/app-api/ai/v1/embed/it-c05-missing"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        // 停用应用的资产路径与启动配置同样不可用
        assertThat(mockMvc.perform(get("/app-api/ai/v1/embed/" + APP_CODE + "/assets/entry-test.js"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mockMvc.perform(get("/app-api/ai/v1/embed/" + APP_CODE + "/bootstrap"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        // 未配置允许域：422（fail-closed，不返回可被任意站点嵌套的壳；状态按错误码常量名推导）
        applicationId = createApplication("it-c05-no-origin", List.of(ORIGIN), true);
        jdbcTemplate.update("UPDATE ai_application SET origins = '[]' WHERE id = ?", applicationId);
        assertThat(mockMvc.perform(get("/app-api/ai/v1/embed/it-c05-no-origin"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(422);
    }

    @Test
    void assetsComeFromManifestOnly() throws Exception {
        applicationId = createApplication(APP_CODE, List.of(ORIGIN), true);
        MockMvc mockMvc = mockMvc();

        MvcResult asset = mockMvc.perform(get("/app-api/ai/v1/embed/" + APP_CODE + "/assets/entry-test.js"))
                .andReturn();
        assertThat(asset.getResponse().getStatus()).isEqualTo(200);
        assertThat(asset.getResponse().getContentType()).contains("text/javascript");
        assertThat(asset.getResponse().getHeader("Cache-Control")).contains("immutable");
        assertThat(asset.getResponse().getContentAsString()).contains("embedFixture");

        // 清单外文件：404（不暴露目录内容）
        assertThat(mockMvc.perform(get("/app-api/ai/v1/embed/" + APP_CODE + "/assets/asset-manifest.json"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        // 目录穿越：任何形式的越界都不提供内容
        int traversalStatus = mockMvc.perform(
                        get("/app-api/ai/v1/embed/" + APP_CODE + "/assets/..%2F..%2Fapplication.yaml"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(traversalStatus).isIn(400, 404);
    }

    @Test
    void bootstrapPublishesAppThemeAndCacheKeyFollowsPublish() throws Exception {
        applicationId = createApplication(APP_CODE, List.of(ORIGIN), true);
        MockMvc mockMvc = mockMvc();

        MvcResult before = mockMvc.perform(get("/app-api/ai/v1/embed/" + APP_CODE + "/bootstrap"))
                .andReturn();
        assertThat(before.getResponse().getStatus()).isEqualTo(200);
        String body = before.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"appCode\":\"" + APP_CODE + "\"")
                .contains("\"protocolVersion\":\"1.0\"")
                .contains("\"allowedOrigins\":[\"" + ORIGIN + "\"]")
                .contains("\"themeSource\":\"PLATFORM_DEFAULT\"") // 来源标记如实给出，不假装已用应用主题
                .doesNotContain("credential")
                .doesNotContain("appSecret");
        String etagBefore = before.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etagBefore).isNotBlank();

        // 命中同一 ETag：304 且不带正文
        MvcResult notModified = mockMvc.perform(get("/app-api/ai/v1/embed/" + APP_CODE + "/bootstrap")
                        .header(HttpHeaders.IF_NONE_MATCH, etagBefore))
                .andReturn();
        assertThat(notModified.getResponse().getStatus()).isEqualTo(304);

        // 发布一份应用主题后：缓存键必须变化，旧 ETag 不再复用
        Long themeId = themeService.create(
                new AiThemeSaveDTO().setApplicationId(applicationId).setTokensJson(tokensJson()));
        themeService.publish(themeId, themeService.getTheme(themeId).getVersion());

        MvcResult after = mockMvc.perform(get("/app-api/ai/v1/embed/" + APP_CODE + "/bootstrap")
                        .header(HttpHeaders.IF_NONE_MATCH, etagBefore))
                .andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(200);
        assertThat(after.getResponse().getHeader(HttpHeaders.ETAG)).isNotEqualTo(etagBefore);
        assertThat(after.getResponse().getContentAsString()).contains("#2563eb").contains("\"themeRevision\":1");
    }

    @Test
    void cacheKeyIsScopedPerApplication() throws Exception {
        applicationId = createApplication(APP_CODE, List.of(ORIGIN), true);
        Long otherId = createApplication("it-c05-other", List.of("https://other.example.com"), true);
        MockMvc mockMvc = mockMvc();

        String first = mockMvc.perform(get("/app-api/ai/v1/embed/" + APP_CODE))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);
        String second = mockMvc.perform(get("/app-api/ai/v1/embed/it-c05-other"))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        assertThat(first).contains(APP_CODE).isNotEqualTo(second);
        assertThat(second).contains("it-c05-other");

        jdbcTemplate.update("DELETE FROM ai_theme WHERE application_id = ?", otherId);
        jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", otherId);
        jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", otherId);
    }
}
