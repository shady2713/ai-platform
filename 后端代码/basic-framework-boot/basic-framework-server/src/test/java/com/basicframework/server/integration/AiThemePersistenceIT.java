package com.basicframework.server.integration;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_APPLICATION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_FONT_NOT_ALLOWED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_REVISION_IMMUTABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_VERSION_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.theme.AiThemeDO;
import com.basicframework.module.ai.dal.mysql.theme.AiThemeMapper;
import com.basicframework.module.ai.domain.theme.AiThemeValidator;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.theme.AiThemeService;
import com.basicframework.module.ai.service.theme.dto.AiThemeEffectiveDTO;
import com.basicframework.module.ai.service.theme.dto.AiThemeSaveDTO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C04 主题持久化与发布（真实 MySQL）。
 *
 * <p>关键不变量在真实数据库上验证：修订号唯一、同一应用最多一个生效修订（**由数据库唯一性兜底**，
 * 不是只靠服务层比对）、发布后不可修改、回退重新指向历史修订、有效主题的继承来源。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiThemePersistenceIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-c04-app";

    private static final String DEFAULT_FONT = AiThemeValidator.ALLOWED_FONT_FAMILIES.get(0);

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiThemeService themeService;

    @Autowired
    private AiThemeMapper themeMapper;

    private Long applicationId;

    @BeforeEach
    void prepareApplication() {
        cleanUp();
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 主题应用")
                .setOrigins(List.of("https://theme.example.com")));
        applicationId = issue.getApplication().getId();
        applicationService.updateStatus(applicationId, 0, true);
    }

    @AfterEach
    void cleanUp() {
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            jdbcTemplate.update("DELETE FROM ai_theme WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
        applicationId = null;
    }

    /** 有效 token JSON（字体栈含引号，必须走 JSON 序列化）。 */
    private static String tokensJson(String fontFamily) {
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("primaryColor", "#1677ff");
        tokens.put("radius", 6);
        tokens.put("fontFamily", fontFamily);
        return JsonUtils.toJsonString(tokens);
    }

    private static AiThemeSaveDTO saveDTO() {
        return new AiThemeSaveDTO()
                .setApplicationId(null)
                .setTokensJson(tokensJson(DEFAULT_FONT))
                .setLayoutJson("{\"fontScale\":\"normal\",\"density\":\"normal\"}");
    }

    private Long createRevision() {
        return themeService.create(saveDTO().setApplicationId(applicationId));
    }

    private long publishedCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_theme WHERE application_id = ? AND publication_state = 'PUBLISHED'",
                Long.class,
                applicationId);
    }

    @Test
    void publishAndRollbackKeepExactlyOneActiveRevision() {
        Long first = createRevision();
        Long second = createRevision();

        AiThemeDO firstRow = themeService.getTheme(first);
        AiThemeDO secondRow = themeService.getTheme(second);
        assertThat(firstRow.getRevision()).isEqualTo(1);
        assertThat(secondRow.getRevision()).isEqualTo(2);
        assertThat(firstRow.getPublicationState()).isEqualTo(AiThemeDO.STATE_DRAFT);
        assertThat(firstRow.getPublicId()).startsWith("thm_");

        // 首次发布：草稿 → 生效
        themeService.publish(first, firstRow.getVersion());
        assertThat(themeService.getTheme(first).getPublicationState()).isEqualTo(AiThemeDO.STATE_PUBLISHED);
        assertThat(publishedCount()).isEqualTo(1);

        // 发布新修订：旧的自动转为 SUPERSEDED，生效修订始终只有一个
        themeService.publish(second, themeService.getTheme(second).getVersion());
        assertThat(themeService.getTheme(first).getPublicationState()).isEqualTo(AiThemeDO.STATE_SUPERSEDED);
        assertThat(themeService.getTheme(second).getPublicationState()).isEqualTo(AiThemeDO.STATE_PUBLISHED);
        assertThat(publishedCount()).isEqualTo(1);

        // 回退：把历史修订重新置为生效，首次发布时间不被篡改
        AiThemeDO secondBefore = themeService.getTheme(second);
        themeService.publish(first, themeService.getTheme(first).getVersion());
        AiThemeDO rolledBack = themeService.getTheme(first);
        assertThat(rolledBack.getPublicationState()).isEqualTo(AiThemeDO.STATE_PUBLISHED);
        assertThat(rolledBack.getPublishedTime()).isNotNull();
        assertThat(themeService.getTheme(second).getPublicationState()).isEqualTo(AiThemeDO.STATE_SUPERSEDED);
        themeService.publish(second, secondBefore.getVersion() + 1);
        assertThat(publishedCount()).isEqualTo(1);
    }

    @Test
    void databaseRejectsSecondActiveRevisionAndDuplicateRevisionNumber() {
        Long first = createRevision();
        Long second = createRevision();
        themeService.publish(first, themeService.getTheme(first).getVersion());

        // 绕过服务层直接改状态：数据库唯一性必须拦住第二个生效修订
        assertThat(catchDataAccessException(() -> jdbcTemplate.update(
                        "UPDATE ai_theme SET publication_state = 'PUBLISHED' WHERE id = ?", second)))
                .isTrue();

        // 修订号在同应用内唯一（并发创建由唯一键兜底）
        assertThat(catchDataAccessException(() -> jdbcTemplate.update(
                        "INSERT INTO ai_theme (public_id, application_id, revision, tokens_json, layout_json,"
                                + " tokens_fingerprint, publication_state, version, creator, updater, deleted)"
                                + " VALUES (?, ?, ?, '{}', '{}', ?, 'DRAFT', 0, 'it', 'it', b'0')",
                        "thm_duplicate0000000000000",
                        applicationId,
                        themeService.getTheme(first).getRevision(),
                        "c".repeat(64))))
                .isTrue();
    }

    @Test
    void invalidTokensFontsAndLayoutsNeverPersist() {
        assertServiceException(
                AI_THEME_FONT_NOT_ALLOWED.getCode(),
                () -> themeService.create(saveDTO()
                        .setApplicationId(applicationId)
                        .setTokensJson(tokensJson("url(https://evil.example.com/f.woff)"))));
        assertServiceException(
                AI_APPLICATION_NOT_FOUND.getCode(),
                () -> themeService.create(saveDTO().setApplicationId(applicationId + 9_999_999L)));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_theme WHERE application_id = ?", Long.class, applicationId))
                .isZero();
    }

    @Test
    void staleOptimisticVersionIsRejectedAndPublishedRevisionIsImmutable() {
        Long revision = createRevision();
        AiThemeDO row = themeService.getTheme(revision);

        themeService.publish(revision, row.getVersion());
        assertServiceException(
                AI_THEME_REVISION_IMMUTABLE.getCode(),
                () -> themeService.publish(
                        revision, themeService.getTheme(revision).getVersion()));

        Long draft = createRevision();
        AiThemeDO draftRow = themeService.getTheme(draft);
        // 乐观锁版本不匹配：CAS 影响 0 行 → 409，且不产生第二个生效修订
        assertServiceException(
                AI_THEME_VERSION_CONFLICT.getCode(), () -> themeService.publish(draft, draftRow.getVersion() + 5));
        assertThat(publishedCount()).isEqualTo(1);

        assertServiceException(AI_THEME_NOT_FOUND.getCode(), () -> themeService.getTheme(99_999_999L));
    }

    @Test
    void effectiveThemeFollowsInheritanceOrder() {
        AiThemeEffectiveDTO fallback = themeService.resolveEffective(applicationId);
        assertThat(fallback.getSource()).isEqualTo(AiThemeEffectiveDTO.SOURCE_PLATFORM_DEFAULT);
        assertThat(fallback.getRevision()).isNull();
        assertThat(fallback.getTokensJson()).isEqualTo(AiThemeValidator.defaultTokensJson());

        Long revision = createRevision();
        AiThemeDO row = themeService.getTheme(revision);
        themeService.publish(revision, row.getVersion());

        AiThemeEffectiveDTO effective = themeService.resolveEffective(applicationId);
        assertThat(effective.getSource()).isEqualTo(AiThemeEffectiveDTO.SOURCE_APPLICATION_PUBLISHED);
        assertThat(effective.getRevision()).isEqualTo(row.getRevision());
        assertThat(effective.getPublicId()).isEqualTo(row.getPublicId());
        assertThat(effective.getFingerprint()).isEqualTo(row.getTokensFingerprint());
        assertThat(effective.getLayoutJson()).contains("\"narrowBreakpoint\":768");
    }

    @Test
    void mapperLookupsCoverIndexedPaths() {
        Long revision = createRevision();
        AiThemeDO row = themeService.getTheme(revision);

        assertThat(themeMapper.selectByPublicId(row.getPublicId()).getId()).isEqualTo(revision);
        assertThat(themeMapper.selectByPublicId("thm_missing")).isNull();
        assertThat(themeMapper
                        .selectByApplicationAndRevision(applicationId, row.getRevision())
                        .getId())
                .isEqualTo(revision);
        assertThat(themeMapper.selectByApplicationAndRevision(applicationId, 999))
                .isNull();
        assertThat(themeMapper.selectMaxRevision(applicationId)).isEqualTo(row.getRevision());
        assertThat(themeMapper.selectPublished(applicationId)).isNull();

        themeService.publish(revision, row.getVersion());
        assertThat(themeMapper.selectPublished(applicationId).getId()).isEqualTo(revision);
        assertThat(themeMapper
                        .selectPage(new PageParam(), applicationId, AiThemeDO.STATE_PUBLISHED)
                        .getList())
                .hasSize(1);
        assertThat(themeMapper
                        .selectPage(new PageParam(), applicationId, AiThemeDO.STATE_DRAFT)
                        .getList())
                .isEmpty();
        assertThat(themeMapper
                        .selectPage(new PageParam(), (Long) null, (String) null)
                        .getTotal())
                .isGreaterThanOrEqualTo(1);

        // CAS 命中与未命中两个分支都要走真实 SQL
        AiThemeDO published = themeService.getTheme(revision);
        assertThat(themeMapper.updateWithVersion(
                        new AiThemeDO().setId(revision).setVersion(published.getVersion() + 1), published.getVersion()))
                .isEqualTo(1);
        assertThat(themeMapper.updateWithVersion(
                        new AiThemeDO().setId(revision).setVersion(published.getVersion() + 2), published.getVersion()))
                .isZero();
    }

    @Test
    void platformDefaultIsAlwaysUsableForFreshApplication() {
        AiApplicationDO application = applicationService.getApplication(applicationId);
        assertThat(application.getAppCode()).isEqualTo(APP_CODE);
        assertThat(themeService.resolveEffective(applicationId).getFingerprint())
                .hasSize(64);
    }

    private static boolean catchDataAccessException(Runnable operation) {
        try {
            operation.run();
            return false;
        } catch (DataAccessException exception) {
            return true;
        }
    }
}
