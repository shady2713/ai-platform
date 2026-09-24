package com.basicframework.module.ai.controller.admin.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.controller.admin.theme.vo.AiThemeEffectiveRespVO;
import com.basicframework.module.ai.controller.admin.theme.vo.AiThemePageReqVO;
import com.basicframework.module.ai.controller.admin.theme.vo.AiThemePublishReqVO;
import com.basicframework.module.ai.controller.admin.theme.vo.AiThemeRespVO;
import com.basicframework.module.ai.controller.admin.theme.vo.AiThemeSaveReqVO;
import com.basicframework.module.ai.dal.dataobject.theme.AiThemeDO;
import com.basicframework.module.ai.domain.theme.AiThemeValidator;
import com.basicframework.module.ai.service.theme.AiThemeService;
import com.basicframework.module.ai.service.theme.dto.AiThemeEffectiveDTO;
import com.basicframework.module.ai.service.theme.dto.AiThemeSaveDTO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;

/** C04 主题控制面契约：权限码与 V79 种子一致、响应不含凭据、端点唯一鉴权策略。 */
class AiThemeControllerTest {

    private static final String DEFAULT_FONT = AiThemeValidator.ALLOWED_FONT_FAMILIES.get(0);

    private final AiThemeService themeService = mock(AiThemeService.class);

    private final AiThemeController controller = new AiThemeController(themeService);

    private static AiThemeDO theme() {
        AiThemeDO theme = new AiThemeDO()
                .setId(41L)
                .setPublicId("thm_abcdefghijklmnopqrstuvwx")
                .setApplicationId(11L)
                .setRevision(2)
                .setTokensJson(AiThemeValidator.defaultTokensJson())
                .setLayoutJson(AiThemeValidator.defaultLayoutJson())
                .setTokensFingerprint("a".repeat(64))
                .setPublicationState(AiThemeDO.STATE_PUBLISHED)
                .setVersion(1);
        theme.setCreateTime(LocalDateTime.now());
        theme.setPublishedTime(LocalDateTime.now());
        return theme;
    }

    private static String tokensJson() {
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("primaryColor", "#1677ff");
        tokens.put("radius", 6);
        tokens.put("fontFamily", DEFAULT_FONT);
        return JsonUtils.toJsonString(tokens);
    }

    private static String permissionOf(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AiThemeController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }

    @Test
    void everyEndpointDeclaresExactlyOnePermissionPolicy() {
        int endpoints = 0;
        for (Method method : AiThemeController.class.getDeclaredMethods()) {
            boolean endpoint = method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) != null
                    || method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) != null
                    || method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class) != null
                    || method.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class) != null;
            if (!endpoint) {
                continue;
            }
            endpoints++;
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            assertThat(annotation)
                    .as("%s 必须且只能声明一个 @PreAuthorize 权限", method.getName())
                    .isNotNull();
            assertThat(annotation.value()).contains("@ss.hasPermission('ai:theme:");
        }
        assertThat(endpoints).as("端点数量与 V79 菜单种子一致").isEqualTo(6);
    }

    @Test
    void permissionsMatchMigrationSeeds() throws Exception {
        assertThat(permissionOf("create", AiThemeSaveReqVO.class)).isEqualTo("ai:theme:create");
        assertThat(permissionOf("publish", AiThemePublishReqVO.class)).isEqualTo("ai:theme:publish");
        assertThat(permissionOf("get", Long.class)).isEqualTo("ai:theme:query");
        assertThat(permissionOf("page", AiThemePageReqVO.class)).isEqualTo("ai:theme:query");
        assertThat(permissionOf("effective", Long.class)).isEqualTo("ai:theme:query");
        assertThat(permissionOf("fonts")).isEqualTo("ai:theme:query");
    }

    @Test
    void responsesNeverCarryCredentials() {
        // 主题协议字段（docs/ai-platform/05 的 tokens_json）以 token 开头，但不是凭据：显式排除
        Set<String> themeTokenFields = Set.of("tokensJson", "tokensFingerprint");
        for (Class<?> voType : List.of(AiThemeRespVO.class, AiThemeEffectiveRespVO.class)) {
            List<String> suspicious = Arrays.stream(voType.getDeclaredFields())
                    .map(Field::getName)
                    .filter(name -> name.matches("(?i).*(credential|password|secret|token|jdbc|ciphertext).*"))
                    .filter(name -> !themeTokenFields.contains(name))
                    .toList();
            assertThat(suspicious).as("%s 不得出现凭据/连接串字段", voType.getSimpleName()).isEmpty();
        }
    }

    @Test
    void delegatesToServiceWithConvertedArguments() {
        when(themeService.create(any())).thenReturn(41L);
        when(themeService.getTheme(41L)).thenReturn(theme());
        when(themeService.getThemePage(any(PageParam.class), eq(11L), eq("PUBLISHED")))
                .thenReturn(new PageResult<>(List.of(theme()), 1L));
        when(themeService.resolveEffective(11L))
                .thenReturn(new AiThemeEffectiveDTO()
                        .setApplicationId(11L)
                        .setPublicId("thm_abcdefghijklmnopqrstuvwx")
                        .setRevision(2)
                        .setFingerprint("a".repeat(64))
                        .setSource(AiThemeEffectiveDTO.SOURCE_APPLICATION_PUBLISHED)
                        .setTokensJson(AiThemeValidator.defaultTokensJson())
                        .setLayoutJson(AiThemeValidator.defaultLayoutJson()));

        assertThat(controller
                        .create(new AiThemeSaveReqVO()
                                .setApplicationId(11L)
                                .setTokensJson(tokensJson())
                                .setLayoutJson("{\"fontScale\":\"large\"}"))
                        .getData())
                .isEqualTo(41L);

        ArgumentCaptor<AiThemeSaveDTO> captor = ArgumentCaptor.forClass(AiThemeSaveDTO.class);
        verify(themeService).create(captor.capture());
        assertThat(captor.getValue().getApplicationId()).isEqualTo(11L);
        assertThat(captor.getValue().getLayoutJson()).isEqualTo("{\"fontScale\":\"large\"}");

        assertThat(controller
                        .publish(new AiThemePublishReqVO().setId(41L).setVersion(0))
                        .getData())
                .isTrue();
        verify(themeService).publish(41L, 0);

        AiThemeRespVO respVO = controller.get(41L).getData();
        assertThat(respVO.getPublicId()).isEqualTo("thm_abcdefghijklmnopqrstuvwx");
        assertThat(respVO.getPublicationState()).isEqualTo(AiThemeDO.STATE_PUBLISHED);
        assertThat(respVO.getTokensFingerprint()).hasSize(64);

        assertThat(controller
                        .page(new AiThemePageReqVO().setApplicationId(11L).setPublicationState("PUBLISHED"))
                        .getData()
                        .getTotal())
                .isEqualTo(1L);

        AiThemeEffectiveRespVO effective = controller.effective(11L).getData();
        assertThat(effective.getSource()).isEqualTo(AiThemeEffectiveDTO.SOURCE_APPLICATION_PUBLISHED);
        assertThat(effective.getRevision()).isEqualTo(2);

        assertThat(controller.fonts().getData()).containsExactlyElementsOf(AiThemeValidator.ALLOWED_FONT_FAMILIES);
    }
}
