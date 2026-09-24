package com.basicframework.module.ai.service.theme;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_APPLICATION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_FONT_NOT_ALLOWED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_REVISION_IMMUTABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_TOKENS_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_VERSION_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.theme.AiThemeDO;
import com.basicframework.module.ai.dal.mysql.theme.AiThemeMapper;
import com.basicframework.module.ai.domain.theme.AiThemeValidator;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.theme.dto.AiThemeEffectiveDTO;
import com.basicframework.module.ai.service.theme.dto.AiThemeSaveDTO;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/** C04 主题修订服务：修订不可变、生效唯一、有效主题解析。 */
class AiThemeServiceImplTest {

    private static final long APPLICATION_ID = 11L;

    private static final String DEFAULT_FONT = AiThemeValidator.ALLOWED_FONT_FAMILIES.get(0);

    private final AiApplicationService applicationService = mock(AiApplicationService.class);

    private final AiThemeMapper themeMapper = mock(AiThemeMapper.class);

    private final AiThemeServiceImpl service = new AiThemeServiceImpl(applicationService, themeMapper);

    /** 有效 token JSON：字体栈里带引号，必须经 JSON 序列化而不是手工拼接。 */
    private static String tokensJson(Object primaryColor, Object radius, String fontFamily) {
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("primaryColor", primaryColor);
        tokens.put("radius", radius);
        tokens.put("fontFamily", fontFamily);
        return JsonUtils.toJsonString(tokens);
    }

    private static AiThemeSaveDTO saveDTO() {
        return new AiThemeSaveDTO()
                .setApplicationId(APPLICATION_ID)
                .setTokensJson(tokensJson("#1677ff", 6, DEFAULT_FONT))
                .setLayoutJson("{\"fontScale\":\"large\"}");
    }

    private static AiThemeDO theme(Long id, Integer revision, String state, Integer version) {
        AiThemeDO theme = new AiThemeDO()
                .setId(id)
                .setPublicId("thm_" + id)
                .setApplicationId(APPLICATION_ID)
                .setRevision(revision)
                .setTokensJson(AiThemeValidator.defaultTokensJson())
                .setLayoutJson(AiThemeValidator.defaultLayoutJson())
                .setTokensFingerprint("f".repeat(64))
                .setPublicationState(state)
                .setVersion(version);
        theme.setCreateTime(LocalDateTime.now());
        return theme;
    }

    private static void assertCode(ErrorCode expected, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(expected.getCode()));
    }

    @Test
    void createValidatesApplicationAndNormalizesRevision() {
        when(applicationService.getApplication(APPLICATION_ID)).thenReturn(new AiApplicationDO().setId(APPLICATION_ID));
        when(themeMapper.selectMaxRevision(APPLICATION_ID)).thenReturn(2);
        when(themeMapper.insert(any(AiThemeDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiThemeDO.class).setId(31L);
            return 1;
        });

        assertThat(service.create(saveDTO())).isEqualTo(31L);

        ArgumentCaptor<AiThemeDO> captor = ArgumentCaptor.forClass(AiThemeDO.class);
        verify(themeMapper).insert(captor.capture());
        AiThemeDO inserted = captor.getValue();
        assertThat(inserted.getRevision()).isEqualTo(3);
        assertThat(inserted.getPublicationState()).isEqualTo(AiThemeDO.STATE_DRAFT);
        assertThat(inserted.getPublicId()).startsWith("thm_").hasSize(28);
        assertThat(inserted.getTokensJson()).doesNotContain("fontScale");
        assertThat(inserted.getLayoutJson())
                .isEqualTo(
                        "{\"fontScale\":\"large\",\"density\":\"normal\",\"narrowBreakpoint\":768,\"minSidebarWidth\":320}");
        assertThat(inserted.getTokensFingerprint()).hasSize(64);
    }

    @Test
    void createWithoutLayoutUsesPlatformDefaults() {
        when(applicationService.getApplication(APPLICATION_ID)).thenReturn(new AiApplicationDO().setId(APPLICATION_ID));
        when(themeMapper.selectMaxRevision(APPLICATION_ID)).thenReturn(0);
        when(themeMapper.insert(any(AiThemeDO.class))).thenReturn(1);

        service.create(saveDTO().setLayoutJson(null));
        service.create(saveDTO().setLayoutJson("  "));

        ArgumentCaptor<AiThemeDO> captor = ArgumentCaptor.forClass(AiThemeDO.class);
        verify(themeMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(theme -> assertThat(theme.getLayoutJson()).isEqualTo(AiThemeValidator.defaultLayoutJson()));
    }

    @Test
    void createRejectsMissingApplicationAndInvalidTokens() {
        assertCode(AI_REQUEST_INVALID, () -> service.create(null));
        assertCode(AI_REQUEST_INVALID, () -> service.create(new AiThemeSaveDTO().setTokensJson("{}")));
        assertCode(AI_REQUEST_INVALID, () -> service.create(saveDTO().setApplicationId(null)));

        when(applicationService.getApplication(APPLICATION_ID)).thenReturn(new AiApplicationDO().setId(APPLICATION_ID));
        assertCode(
                AI_THEME_TOKENS_INVALID, () -> service.create(saveDTO().setTokensJson("{\"primaryColor\":\"red\"}")));
        assertCode(
                AI_THEME_FONT_NOT_ALLOWED,
                () -> service.create(saveDTO()
                        .setTokensJson("{\"primaryColor\":\"#1677ff\",\"radius\":6," + "\"fontFamily\":\"Arial\"}")));
        verify(themeMapper, never()).insert(any(AiThemeDO.class));
    }

    @Test
    void publishSupersedesCurrentAndActivatesTarget() {
        when(themeMapper.selectById(5L)).thenReturn(theme(5L, 2, AiThemeDO.STATE_DRAFT, 0));
        when(themeMapper.selectPublished(APPLICATION_ID)).thenReturn(theme(4L, 1, AiThemeDO.STATE_PUBLISHED, 3));
        when(themeMapper.updateWithVersion(any(AiThemeDO.class), anyInt())).thenReturn(1);

        service.publish(5L, 0);

        ArgumentCaptor<AiThemeDO> captor = ArgumentCaptor.forClass(AiThemeDO.class);
        verify(themeMapper, times(2)).updateWithVersion(captor.capture(), anyInt());
        List<AiThemeDO> updates = captor.getAllValues();
        assertThat(updates.get(0).getPublicationState()).isEqualTo(AiThemeDO.STATE_SUPERSEDED);
        assertThat(updates.get(0).getVersion()).isEqualTo(4);
        assertThat(updates.get(1).getPublicationState()).isEqualTo(AiThemeDO.STATE_PUBLISHED);
        assertThat(updates.get(1).getVersion()).isEqualTo(1);
        assertThat(updates.get(1).getPublishedTime()).isNotNull();
    }

    @Test
    void publishKeepsFirstPublishedTimeOnRollback() {
        LocalDateTime firstPublished = LocalDateTime.now().minusDays(3);
        AiThemeDO historical = theme(5L, 1, AiThemeDO.STATE_SUPERSEDED, 4);
        historical.setPublishedTime(firstPublished);
        when(themeMapper.selectById(5L)).thenReturn(historical);
        when(themeMapper.selectPublished(APPLICATION_ID)).thenReturn(theme(6L, 2, AiThemeDO.STATE_PUBLISHED, 0));
        when(themeMapper.updateWithVersion(any(AiThemeDO.class), anyInt())).thenReturn(1);

        service.publish(5L, 4);

        ArgumentCaptor<AiThemeDO> captor = ArgumentCaptor.forClass(AiThemeDO.class);
        verify(themeMapper, times(2)).updateWithVersion(captor.capture(), anyInt());
        // 回退不篡改首次发布时间
        assertThat(captor.getAllValues().get(1).getPublishedTime()).isNull();
    }

    @Test
    void publishRejectsAlreadyPublishedRevisionAndConcurrencyConflicts() {
        when(themeMapper.selectById(5L)).thenReturn(theme(5L, 1, AiThemeDO.STATE_PUBLISHED, 1));
        assertCode(AI_THEME_REVISION_IMMUTABLE, () -> service.publish(5L, 1));

        when(themeMapper.selectById(5L)).thenReturn(theme(5L, 2, AiThemeDO.STATE_DRAFT, 0));
        when(themeMapper.selectPublished(APPLICATION_ID)).thenReturn(null);
        when(themeMapper.updateWithVersion(any(AiThemeDO.class), anyInt())).thenReturn(0);
        assertCode(AI_THEME_VERSION_CONFLICT, () -> service.publish(5L, 0));

        when(themeMapper.selectById(5L)).thenReturn(theme(5L, 2, AiThemeDO.STATE_DRAFT, 0));
        when(themeMapper.selectPublished(APPLICATION_ID)).thenReturn(theme(4L, 1, AiThemeDO.STATE_PUBLISHED, 3));
        when(themeMapper.updateWithVersion(any(AiThemeDO.class), anyInt())).thenReturn(0);
        assertCode(AI_THEME_VERSION_CONFLICT, () -> service.publish(5L, 0));
    }

    @Test
    void publishMapsDatabaseUniqueViolationToConflict() {
        when(themeMapper.selectById(5L)).thenReturn(theme(5L, 2, AiThemeDO.STATE_DRAFT, 0));
        when(themeMapper.selectPublished(APPLICATION_ID)).thenReturn(null);
        when(themeMapper.updateWithVersion(any(AiThemeDO.class), anyInt()))
                .thenThrow(new DuplicateKeyException("uk_ai_theme_published"));

        assertCode(AI_THEME_VERSION_CONFLICT, () -> service.publish(5L, 0));
    }

    @Test
    void publishUsesProvidedVersionWhenPresent() {
        when(themeMapper.selectById(5L)).thenReturn(theme(5L, 2, AiThemeDO.STATE_DRAFT, 7));
        when(themeMapper.selectPublished(APPLICATION_ID)).thenReturn(null);
        when(themeMapper.updateWithVersion(any(AiThemeDO.class), anyInt())).thenReturn(1);

        service.publish(5L, 3);

        verify(themeMapper).updateWithVersion(any(AiThemeDO.class), eq(3));
    }

    @Test
    void getThemeRejectsMissingIdOrRow() {
        assertCode(AI_THEME_NOT_FOUND, () -> service.getTheme(null));
        assertCode(AI_THEME_NOT_FOUND, () -> service.getTheme(404L));
    }

    @Test
    void pageDelegatesFilters() {
        when(themeMapper.selectPage(any(PageParam.class), eq(APPLICATION_ID), eq("DRAFT")))
                .thenReturn(new PageResult<>(List.of(theme(1L, 1, AiThemeDO.STATE_DRAFT, 0)), 1L));

        PageResult<AiThemeDO> page = service.getThemePage(new PageParam(), APPLICATION_ID, "DRAFT");

        assertThat(page.getTotal()).isEqualTo(1L);
        verify(themeMapper).selectPage(any(PageParam.class), eq(APPLICATION_ID), eq("DRAFT"));
    }

    @Test
    void resolveEffectiveFallsBackToPlatformDefault() {
        when(applicationService.getApplication(APPLICATION_ID)).thenReturn(new AiApplicationDO().setId(APPLICATION_ID));
        when(themeMapper.selectPublished(APPLICATION_ID)).thenReturn(null);

        AiThemeEffectiveDTO effective = service.resolveEffective(APPLICATION_ID);

        assertThat(effective.getSource()).isEqualTo(AiThemeEffectiveDTO.SOURCE_PLATFORM_DEFAULT);
        assertThat(effective.getRevision()).isNull();
        assertThat(effective.getPublicId()).isNull();
        assertThat(effective.getTokensJson()).isEqualTo(AiThemeValidator.defaultTokensJson());
        assertThat(effective.getFingerprint()).hasSize(64);
    }

    @Test
    void resolveEffectiveReturnsPublishedRevision() {
        AiThemeDO published = theme(9L, 4, AiThemeDO.STATE_PUBLISHED, 1);
        when(applicationService.getApplication(APPLICATION_ID)).thenReturn(new AiApplicationDO().setId(APPLICATION_ID));
        when(themeMapper.selectPublished(APPLICATION_ID)).thenReturn(published);

        AiThemeEffectiveDTO effective = service.resolveEffective(APPLICATION_ID);

        assertThat(effective.getSource()).isEqualTo(AiThemeEffectiveDTO.SOURCE_APPLICATION_PUBLISHED);
        assertThat(effective.getRevision()).isEqualTo(4);
        assertThat(effective.getPublicId()).isEqualTo("thm_9");
        assertThat(effective.getFingerprint()).isEqualTo("f".repeat(64));
    }

    @Test
    void resolveEffectiveRejectsMissingApplication() {
        assertCode(AI_REQUEST_INVALID, () -> service.resolveEffective(null));
        when(applicationService.getApplication(APPLICATION_ID)).thenThrow(exception(AI_APPLICATION_NOT_FOUND));
        assertCode(AI_APPLICATION_NOT_FOUND, () -> service.resolveEffective(APPLICATION_ID));
        verify(themeMapper, never()).selectPublished(anyLong());
    }
}
