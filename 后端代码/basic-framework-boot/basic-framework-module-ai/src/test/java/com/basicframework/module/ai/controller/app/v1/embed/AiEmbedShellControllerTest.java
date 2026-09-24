package com.basicframework.module.ai.controller.app.v1.embed;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EMBED_APP_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EMBED_ASSETS_NOT_STAGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EMBED_ASSET_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EMBED_ORIGIN_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.module.ai.controller.app.v1.embed.vo.AiEmbedBootstrapRespVO;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.service.theme.AiThemeService;
import com.basicframework.module.ai.service.theme.dto.AiThemeEffectiveDTO;
import jakarta.annotation.security.PermitAll;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

/** C05 嵌入入口：策略唯一、允许域 fail-closed、缓存键随应用配置与主题变化。 */
class AiEmbedShellControllerTest {

    private static final String APP_CODE = "crm-portal";

    private static AiApplicationDO application(String originsJson, boolean enabled, int version) {
        return new AiApplicationDO()
                .setId(7L)
                .setAppCode(APP_CODE)
                .setName("CRM 门户")
                .setOrigins(originsJson)
                .setEnabled(enabled)
                .setVersion(version);
    }

    private static AiThemeEffectiveDTO effective(String fingerprint, Integer revision) {
        return new AiThemeEffectiveDTO()
                .setApplicationId(7L)
                .setFingerprint(fingerprint)
                .setRevision(revision)
                .setSource(AiThemeEffectiveDTO.SOURCE_APPLICATION_PUBLISHED)
                .setTokensJson("{\"primaryColor\":\"#1677ff\",\"radius\":6,\"fontFamily\":\"system-ui, sans-serif\"}")
                .setLayoutJson("{\"fontScale\":\"normal\",\"density\":\"normal\"}");
    }

    private static AiEmbedProperties propertiesOf(Path directory) {
        AiEmbedProperties properties = new AiEmbedProperties();
        properties.setAssetsDirectory(directory.toString());
        return properties;
    }

    private static void stageAssets(Path directory) throws IOException {
        Files.createDirectories(directory.resolve("assets"));
        Files.writeString(directory.resolve("assets").resolve("entry-a1.js"), "console.log('embed')");
        Files.writeString(directory.resolve("assets").resolve("entry-a1.css"), "body{}");
        Files.writeString(
                directory.resolve("asset-manifest.json"),
                "{\"version\":1,\"entryJs\":\"entry-a1.js\",\"entryCss\":\"entry-a1.css\","
                        + "\"files\":{\"entry-a1.js\":1,\"entry-a1.css\":1}}");
    }

    private record Fixture(AiEmbedShellController controller, AiEmbedApplicationResolver resolver) {}

    private static Fixture fixture(Path directory, AiApplicationDO application, AiThemeEffectiveDTO effective) {
        AiEmbedApplicationResolver resolver = mock(AiEmbedApplicationResolver.class);
        when(resolver.resolveEnabled(APP_CODE)).thenReturn(application);
        AiThemeService themeService = mock(AiThemeService.class);
        when(themeService.resolveEffective(7L)).thenReturn(effective);
        return new Fixture(
                new AiEmbedShellController(resolver, new AiEmbedAssetCatalog(propertiesOf(directory)), themeService),
                resolver);
    }

    private static void assertCode(int expected, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(expected));
    }

    @Test
    void everyEndpointIsAnonymousAndDeclaresNoScopeGuard() throws Exception {
        int endpoints = 0;
        for (Method method : AiEmbedShellController.class.getDeclaredMethods()) {
            if (method.getAnnotation(GetMapping.class) == null) {
                continue;
            }
            endpoints++;
            assertThat(method.getAnnotation(PermitAll.class))
                    .as("%s 必须是公开端点", method.getName())
                    .isNotNull();
            assertThat(method.getAnnotation(PreAuthorize.class))
                    .as("%s 不得声明 scope 守卫（公开启动壳）", method.getName())
                    .isNull();
        }
        assertThat(endpoints).as("嵌入端点数量").isEqualTo(3);
    }

    @Test
    void bootstrapExposesOnlyPublicFields() {
        List<String> fields = Arrays.stream(AiEmbedBootstrapRespVO.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fields)
                .contains("appCode", "protocolVersion", "allowedOrigins", "themeFingerprint", "tokensJson")
                .noneMatch(name -> name.matches("(?i).*(credential|secret|password|jdbc|ciphertext).*"))
                .doesNotContain("token", "appSecret", "credentialCiphertext");
    }

    @Test
    void shellRendersFixedHtmlWithExactAncestorsAndRevalidatingCache(@TempDir Path directory) throws IOException {
        stageAssets(directory);
        AiEmbedShellController controller = fixture(
                        directory, application("[\"https://crm.example.com\"]", true, 3), effective("fp-1", 2))
                .controller();

        ResponseEntity<String> response = controller.shell(APP_CODE, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).hasToString("text/html");
        assertThat(response.getBody())
                .contains("<!doctype html>")
                .contains("/app-api/ai/v1/embed/" + APP_CODE + "/assets/entry-a1.js")
                .contains("/app-api/ai/v1/embed/" + APP_CODE + "/assets/entry-a1.css")
                .contains("data-app-code=\"" + APP_CODE + "\"")
                // 壳里没有内联脚本与内联样式：主题由 bootstrap + CSS 变量驱动（CSP 无 unsafe-inline）
                .doesNotContain("<script>")
                .doesNotContain("style=");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                .contains("frame-ancestors https://crm.example.com");
        assertThat(response.getHeaders().getCacheControl()).contains("no-cache").contains("must-revalidate");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"embed-crm-portal-3-fp-1\"");
    }

    @Test
    void shellRevalidatesToNotModifiedWhenNothingChanged(@TempDir Path directory) throws IOException {
        stageAssets(directory);
        AiEmbedShellController controller = fixture(
                        directory, application("[\"https://crm.example.com\"]", true, 3), effective("fp-1", 2))
                .controller();

        ResponseEntity<String> notModified = controller.shell(APP_CODE, "\"embed-crm-portal-3-fp-1\"");
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getBody()).isNull();
        assertThat(notModified.getHeaders().getFirst("Content-Security-Policy"))
                .contains("frame-ancestors https://crm.example.com");

        // 主题指纹变化（发布新修订）后 ETag 必须不同：浏览器不会继续用旧外观
        AiEmbedShellController republished = fixture(
                        directory, application("[\"https://crm.example.com\"]", true, 3), effective("fp-2", 3))
                .controller();
        assertThat(republished.shell(APP_CODE, "\"embed-crm-portal-3-fp-1\"").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void shellFailsClosedWithoutUsableOrigins(@TempDir Path directory) throws IOException {
        stageAssets(directory);
        assertCode(AI_EMBED_ORIGIN_INVALID.getCode(), () -> fixture(
                        directory, application("[]", true, 1), effective("fp", 1))
                .controller()
                .shell(APP_CODE, null));
        assertCode(AI_EMBED_ORIGIN_INVALID.getCode(), () -> fixture(
                        directory, application("[\"*\"]", true, 1), effective("fp", 1))
                .controller()
                .shell(APP_CODE, null));
    }

    @Test
    void shellRequiresStagedAssets(@TempDir Path directory) {
        assertCode(AI_EMBED_ASSETS_NOT_STAGED.getCode(), () -> fixture(
                        directory, application("[\"https://crm.example.com\"]", true, 1), effective("fp", 1))
                .controller()
                .shell(APP_CODE, null));
    }

    @Test
    void unknownOrDisabledApplicationIsNotFound(@TempDir Path directory) throws IOException {
        stageAssets(directory);
        Fixture fixture = fixture(directory, application("[\"https://crm.example.com\"]", true, 1), effective("fp", 1));
        when(fixture.resolver().resolveEnabled(APP_CODE))
                .thenThrow(com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception(
                        AI_EMBED_APP_NOT_EXISTS));

        assertCode(AI_EMBED_APP_NOT_EXISTS.getCode(), () -> fixture.controller().shell(APP_CODE, null));
        assertCode(AI_EMBED_APP_NOT_EXISTS.getCode(), () -> fixture.controller().bootstrap(APP_CODE, null));
        assertCode(AI_EMBED_APP_NOT_EXISTS.getCode(), () -> fixture.controller().asset(APP_CODE, "entry-a1.js"));
    }

    @Test
    void bootstrapReturnsPublicConfigurationWithThemeAndRevalidates(@TempDir Path directory) throws IOException {
        stageAssets(directory);
        AiEmbedShellController controller = fixture(
                        directory, application("[\"https://crm.example.com\"]", true, 3), effective("fp-1", 2))
                .controller();

        CommonResult<AiEmbedBootstrapRespVO> result =
                controller.bootstrap(APP_CODE, null).getBody();

        assertThat(result).isNotNull();
        AiEmbedBootstrapRespVO data = result.getData();
        assertThat(data.getAppCode()).isEqualTo(APP_CODE);
        assertThat(data.getProtocolVersion()).isEqualTo("1.0");
        assertThat(data.getAllowedOrigins()).containsExactly("https://crm.example.com");
        assertThat(data.getBrandName()).isEqualTo("CRM 门户");
        assertThat(data.getAssetsBase()).isEqualTo("/app-api/ai/v1/embed/" + APP_CODE + "/assets");
        assertThat(data.getThemeRevision()).isEqualTo(2);
        assertThat(data.getThemeFingerprint()).isEqualTo("fp-1");
        assertThat(data.getTokensJson()).contains("primaryColor");
        assertThat(data.getLayoutJson()).contains("fontScale");

        ResponseEntity<CommonResult<AiEmbedBootstrapRespVO>> notModified =
                controller.bootstrap(APP_CODE, "\"embed-crm-portal-3-fp-1\"");
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getBody()).isNull();
    }

    @Test
    void assetServesOnlyManifestEntriesWithImmutableCaching(@TempDir Path directory) throws IOException {
        stageAssets(directory);
        AiEmbedShellController controller = fixture(
                        directory, application("[\"https://crm.example.com\"]", true, 1), effective("fp", 1))
                .controller();

        ResponseEntity<byte[]> asset = controller.asset(APP_CODE, "entry-a1.js");
        assertThat(asset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asset.getHeaders().getContentType()).hasToString("text/javascript");
        assertThat(asset.getHeaders().getCacheControl()).contains("immutable").contains("max-age=31536000");
        assertThat(new String(asset.getBody())).isEqualTo("console.log('embed')");

        assertCode(AI_EMBED_ASSET_NOT_EXISTS.getCode(), () -> controller.asset(APP_CODE, "package.json"));
        assertCode(AI_EMBED_ASSET_NOT_EXISTS.getCode(), () -> controller.asset(APP_CODE, ".env"));
    }

    @Test
    void assetPathAlsoRequiresEnabledApplicationWithUsableOrigins(@TempDir Path directory) throws IOException {
        stageAssets(directory);
        assertCode(AI_EMBED_ORIGIN_INVALID.getCode(), () -> fixture(
                        directory, application("[]", true, 1), effective("fp", 1))
                .controller()
                .asset(APP_CODE, "entry-a1.js"));
    }
}
