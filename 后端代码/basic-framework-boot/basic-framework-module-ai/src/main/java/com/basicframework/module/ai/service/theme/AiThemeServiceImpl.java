package com.basicframework.module.ai.service.theme;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_REVISION_IMMUTABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_THEME_VERSION_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.theme.AiThemeDO;
import com.basicframework.module.ai.dal.mysql.theme.AiThemeMapper;
import com.basicframework.module.ai.domain.theme.AiThemeValidator;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.theme.dto.AiThemeEffectiveDTO;
import com.basicframework.module.ai.service.theme.dto.AiThemeSaveDTO;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * 主题修订服务（C04）。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>修订不可变</b>：tokens/布局只在创建时写入；发布后修改一律拒绝（{@code AI_THEME_REVISION_IMMUTABLE}），
 *       调整等于新建修订——回退因此总能指向一份**内容确定**的历史修订；</li>
 *   <li><b>同一应用最多一个生效修订</b>：由数据库的 `uk_ai_theme_published` 唯一性承担并发保障，
 *       服务层先做同事务比对（给出可读的 409），数据库冲突再兜底转成同一错误码；</li>
 *   <li><b>有效主题是解析结果而不是存储值</b>：平台默认 → 应用已发布修订 → 允许字段的宿主运行时覆盖，
 *       覆盖不落库、不影响其他应用，所以本服务只提供"到已发布修订为止"的解析。</li>
 * </ol>
 */
@Service
@Validated
@RequiredArgsConstructor
public class AiThemeServiceImpl implements AiThemeService {

    private static final int PUBLIC_ID_RANDOM_LENGTH = 24;

    private final AiApplicationService applicationService;

    private final AiThemeMapper themeMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiThemeSaveDTO saveDTO) {
        if (saveDTO == null || saveDTO.getApplicationId() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        // 应用必须存在（不存在即 404）：主题归属由 FK 与这里的前置校验共同保证
        applicationService.getApplication(saveDTO.getApplicationId());
        String tokensJson = AiThemeValidator.validateTokens(saveDTO.getTokensJson());
        String layoutJson = AiThemeValidator.validateLayout(
                saveDTO.getLayoutJson() == null || saveDTO.getLayoutJson().isBlank() ? "{}" : saveDTO.getLayoutJson());
        AiThemeDO theme = new AiThemeDO()
                .setPublicId(nextPublicId())
                .setApplicationId(saveDTO.getApplicationId())
                .setRevision(themeMapper.selectMaxRevision(saveDTO.getApplicationId()) + 1)
                .setTokensJson(tokensJson)
                .setLayoutJson(layoutJson)
                .setTokensFingerprint(AiThemeValidator.fingerprint(tokensJson, layoutJson))
                .setPublicationState(AiThemeDO.STATE_DRAFT)
                .setVersion(0);
        themeMapper.insert(theme);
        return theme.getId();
    }

    @Override
    public AiThemeDO getTheme(Long id) {
        AiThemeDO theme = id == null ? null : themeMapper.selectById(id);
        if (theme == null) {
            throw exception(AI_THEME_NOT_FOUND);
        }
        return theme;
    }

    @Override
    public PageResult<AiThemeDO> getThemePage(PageParam pageParam, Long applicationId, String publicationState) {
        return themeMapper.selectPage(pageParam, applicationId, publicationState);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long id, Integer version) {
        AiThemeDO target = getTheme(id);
        if (AiThemeDO.STATE_PUBLISHED.equals(target.getPublicationState())) {
            // 已经是当前生效修订：重复点"发布"不是并发冲突，而是无效操作
            throw exception(AI_THEME_REVISION_IMMUTABLE);
        }
        LocalDateTime now = LocalDateTime.now();
        AiThemeDO current = themeMapper.selectPublished(target.getApplicationId());
        if (current != null && !current.getId().equals(target.getId())) {
            supersede(current);
        }
        try {
            activate(target, version, now);
        } catch (DuplicateKeyException exception) {
            // 并发首次发布：数据库唯一性判出的赢家已生效，本事务整体回滚成 409
            throw exception(AI_THEME_VERSION_CONFLICT);
        }
    }

    @Override
    public AiThemeEffectiveDTO resolveEffective(Long applicationId) {
        if (applicationId == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        applicationService.getApplication(applicationId);
        AiThemeDO published = themeMapper.selectPublished(applicationId);
        if (published == null) {
            String tokensJson = AiThemeValidator.defaultTokensJson();
            String layoutJson = AiThemeValidator.defaultLayoutJson();
            return new AiThemeEffectiveDTO()
                    .setApplicationId(applicationId)
                    .setFingerprint(AiThemeValidator.fingerprint(tokensJson, layoutJson))
                    .setSource(AiThemeEffectiveDTO.SOURCE_PLATFORM_DEFAULT)
                    .setTokensJson(tokensJson)
                    .setLayoutJson(layoutJson);
        }
        return new AiThemeEffectiveDTO()
                .setApplicationId(applicationId)
                .setPublicId(published.getPublicId())
                .setRevision(published.getRevision())
                .setFingerprint(published.getTokensFingerprint())
                .setSource(AiThemeEffectiveDTO.SOURCE_APPLICATION_PUBLISHED)
                .setTokensJson(published.getTokensJson())
                .setLayoutJson(published.getLayoutJson());
    }

    /** 把当前生效修订置为"已被取代"；CAS 失败说明有人同时发布，直接 409。 */
    private void supersede(AiThemeDO current) {
        int updated = themeMapper.updateWithVersion(
                new AiThemeDO()
                        .setId(current.getId())
                        .setPublicationState(AiThemeDO.STATE_SUPERSEDED)
                        .setVersion(current.getVersion() + 1),
                current.getVersion());
        if (updated == 0) {
            throw exception(AI_THEME_VERSION_CONFLICT);
        }
    }

    /** 激活目标修订；{@code publishedTime} 只在首次发布时写入（回退不篡改首次发布时间）。 */
    private void activate(AiThemeDO target, Integer version, LocalDateTime now) {
        AiThemeDO update = new AiThemeDO()
                .setId(target.getId())
                .setPublicationState(AiThemeDO.STATE_PUBLISHED)
                .setVersion(target.getVersion() + 1);
        if (target.getPublishedTime() == null) {
            update.setPublishedTime(now);
        }
        int updated = themeMapper.updateWithVersion(update, version == null ? target.getVersion() : version);
        if (updated == 0) {
            throw exception(AI_THEME_VERSION_CONFLICT);
        }
    }

    /** 主题对外标识：类型前缀 + 随机串（与 run_/rpt_ 同一口径，不由前端解析内部编号）。 */
    private String nextPublicId() {
        return "thm_" + UUID.randomUUID().toString().replace("-", "").substring(0, PUBLIC_ID_RANDOM_LENGTH);
    }
}
