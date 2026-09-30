package com.basicframework.module.ai.service.semantic;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ACCESS_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CALIBER_MISSING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_DISABLED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_FINGERPRINT_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_PUBLISHER_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_REVISION_EXPIRED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_REVISION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_SEMANTICS_REVISION_NOT_PUBLISHED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMetricSemanticsDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMetricSemanticsRevisionDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMetricSemanticsMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMetricSemanticsRevisionMapper;
import com.basicframework.module.ai.domain.semantic.AiMetricSemantics;
import com.basicframework.module.ai.domain.semantic.AiMetricSemanticsFacts;
import com.basicframework.module.ai.service.semantic.dto.AiMetricSemanticsRevisionDraftDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMetricSemanticsSaveDTO;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨源指标口径实现（Y03）。
 *
 * <p>实现要点（每条都有负向测试）：
 * <ol>
 *   <li><b>登记即校验</b>：口径定义在写入草稿前先经 {@link AiMetricSemantics} 解析，
 *       币种/单位/时区/粒度/聚合顺序任一不合规就写不进去——脏口径不会进库等聚合时再炸；</li>
 *   <li><b>发布推进 current_revision 用口径乐观锁 CAS</b>，发布本身用版本乐观锁 CAS，
 *       两步在同一事务里，任一步失败都不产生"版本已发布但口径没指向它"的中间态；</li>
 *   <li><b>发布即冻结指纹</b>：指纹由口径定义的规范化内容重算，读取时重算比对，
 *       不符即阻断（口径被版本外改动）；</li>
 *   <li><b>无操作员身份不能登记口径</b>：口径决定了"哪些数可以相加"，不能由匿名调用写入。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiMetricSemanticsServiceImpl implements AiMetricSemanticsService {

    /** 口径标识：与 QueryPlan/口径定义同一模式。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private final AiMetricSemanticsMapper semanticsMapper;

    private final AiMetricSemanticsRevisionMapper revisionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createSemantics(AiMetricSemanticsSaveDTO saveDTO) {
        requireOperator();
        String metricCode = requireCode(saveDTO == null ? null : saveDTO.getMetricCode());
        if (semanticsMapper.selectByCode(metricCode) != null) {
            throw exception(AI_METRIC_SEMANTICS_CODE_DUPLICATE);
        }
        AiMetricSemanticsDO created = new AiMetricSemanticsDO()
                .setMetricCode(metricCode)
                .setMetricName(trimToEmpty(saveDTO.getMetricName(), 128))
                .setDescription(trimToEmpty(saveDTO.getDescription(), 512))
                .setStatus(AiMetricSemanticsDO.STATUS_ACTIVE)
                .setCurrentRevision(0L)
                .setVersion(0);
        semanticsMapper.insert(created);
        return created.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSemanticsStatus(Long id, Integer version, boolean enabled) {
        requireOperator();
        requireVersion(version);
        AiMetricSemanticsDO semantics = getSemantics(id);
        AiMetricSemanticsDO update = new AiMetricSemanticsDO()
                .setId(semantics.getId())
                .setStatus(enabled ? AiMetricSemanticsDO.STATUS_ACTIVE : AiMetricSemanticsDO.STATUS_DISABLED)
                .setVersion(version + 1);
        if (semanticsMapper.updateWithVersion(update, version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public AiMetricSemanticsDO getSemanticsByCode(String metricCode) {
        AiMetricSemanticsDO semantics = semanticsMapper.selectByCode(trim(metricCode));
        if (semantics == null) {
            throw exception(AI_METRIC_SEMANTICS_NOT_EXISTS);
        }
        return semantics;
    }

    @Override
    public PageResult<AiMetricSemanticsDO> getSemanticsPage(PageParam pageParam, String status, String keyword) {
        return semanticsMapper.selectPage(pageParam, trim(status), trim(keyword));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRevision(AiMetricSemanticsRevisionDraftDTO draftDTO) {
        Long operator = requireOperator();
        if (draftDTO == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiMetricSemanticsDO semantics = getSemanticsByCode(draftDTO.getMetricCode());
        // 登记即校验：不合规的口径写不进草稿（脏数据不会留到聚合阶段才暴露）
        AiMetricSemantics definition = AiMetricSemantics.parse(draftDTO.getDefinitionJson());
        if (!AiMetricSemanticsFacts.complete(definition.sources())) {
            throw exception(AI_METRIC_CALIBER_MISSING_CONFLICT);
        }
        AiMetricSemanticsRevisionDO draft = new AiMetricSemanticsRevisionDO()
                .setMetricSemanticsId(semantics.getId())
                .setRevisionNo(nextRevisionNo(semantics.getId()))
                .setStatus(AiMetricSemanticsRevisionDO.STATUS_DRAFT)
                .setDefinitionJson(definition.canonicalJson())
                .setDefinitionFingerprint("")
                .setValidFrom(requireTime(draftDTO.getValidFrom()))
                .setValidTo(parseTime(draftDTO.getValidTo()))
                .setCreatedBy(operator)
                .setVersion(0);
        revisionMapper.insert(draft);
        return draft.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMetricSemanticsRevisionDO publishRevision(Long metricSemanticsId, Long revisionNo, Integer version) {
        Long operator = requireOperator();
        requireVersion(version);
        AiMetricSemanticsDO semantics = getSemantics(metricSemanticsId);
        AiMetricSemanticsRevisionDO revision = getRevision(metricSemanticsId, revisionNo);
        if (!AiMetricSemanticsRevisionDO.STATUS_DRAFT.equals(revision.getStatus())) {
            throw exception(AI_STATE_CONFLICT);
        }
        if (operator.equals(revision.getCreatedBy())) {
            // 独立审核：草稿创建人不能自己发布（否则"审核"只是形式）
            throw exception(AI_METRIC_SEMANTICS_PUBLISHER_CONFLICT);
        }
        // 发布只负责"把声明冻结成可核验事实"：解析（结构/枚举/键白名单）+ 指纹冻结 + 独立审核。
        //
        // 刻意**不**在这里做"来源口径必须与口径一致/币种必须可加"的检查：那两项恰恰是
        // 跨源聚合要拒绝的**业务事实**（AT-034 的币种与时区冲突）。如果发布时就拦掉，
        // 聚合侧的门就永远走不到，两处判定也会各说各话。口径是"声明意图"，
        // 能不能真的相加由聚合校验器按版本判定——单一真源，不在两处重复判定。
        AiMetricSemantics definition = AiMetricSemantics.parse(revision.getDefinitionJson());
        String fingerprint = definition.definitionHash();
        AiMetricSemanticsRevisionDO publish = new AiMetricSemanticsRevisionDO()
                .setId(revision.getId())
                .setStatus(AiMetricSemanticsRevisionDO.STATUS_PUBLISHED)
                .setDefinitionFingerprint(fingerprint)
                .setPublishedBy(operator)
                .setPublishedTime(LocalDateTime.now())
                .setVersion(version + 1);
        if (revisionMapper.publishWithVersion(publish, version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        AiMetricSemanticsDO advance = new AiMetricSemanticsDO()
                .setId(semantics.getId())
                .setCurrentRevision(revisionNo)
                .setVersion(semantics.getVersion() + 1);
        if (semanticsMapper.updateWithVersion(advance, semantics.getVersion()) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return getRevision(metricSemanticsId, revisionNo);
    }

    @Override
    public AiMetricSemantics resolveVerified(String metricCode, Long revisionNo, LocalDateTime asOf) {
        AiMetricSemanticsDO semantics = getSemanticsByCode(metricCode);
        if (!AiMetricSemanticsDO.STATUS_ACTIVE.equals(semantics.getStatus())) {
            throw exception(AI_METRIC_SEMANTICS_DISABLED_CONFLICT);
        }
        AiMetricSemanticsRevisionDO revision = getRevision(semantics.getId(), revisionNo);
        if (!AiMetricSemanticsRevisionDO.STATUS_PUBLISHED.equals(revision.getStatus())) {
            throw exception(AI_METRIC_SEMANTICS_REVISION_NOT_PUBLISHED_CONFLICT);
        }
        if (asOf == null
                || revision.getValidFrom() == null
                || asOf.isBefore(revision.getValidFrom())
                || (revision.getValidTo() != null && !asOf.isBefore(revision.getValidTo()))) {
            throw exception(AI_METRIC_SEMANTICS_REVISION_EXPIRED_CONFLICT);
        }
        AiMetricSemantics definition = AiMetricSemantics.parse(revision.getDefinitionJson());
        if (!AiMetricSemanticsFacts.digest(definition).equals(revision.getDefinitionFingerprint())) {
            // 冻结指纹与重算结果不一致：内容被版本外改动，按这个版本解释历史报表已经不安全
            throw exception(AI_METRIC_SEMANTICS_FINGERPRINT_CONFLICT);
        }
        return definition;
    }

    private AiMetricSemanticsDO getSemantics(Long id) {
        if (id == null) {
            throw exception(AI_METRIC_SEMANTICS_NOT_EXISTS);
        }
        AiMetricSemanticsDO semantics = semanticsMapper.selectById(id);
        if (semantics == null) {
            throw exception(AI_METRIC_SEMANTICS_NOT_EXISTS);
        }
        return semantics;
    }

    private AiMetricSemanticsRevisionDO getRevision(Long metricSemanticsId, Long revisionNo) {
        AiMetricSemanticsRevisionDO revision = revisionMapper.selectByRevisionNo(metricSemanticsId, revisionNo);
        if (revision == null) {
            throw exception(AI_METRIC_SEMANTICS_REVISION_NOT_EXISTS);
        }
        return revision;
    }

    private Long nextRevisionNo(Long metricSemanticsId) {
        List<AiMetricSemanticsRevisionDO> existing = revisionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiMetricSemanticsRevisionDO>()
                        .eq(AiMetricSemanticsRevisionDO::getMetricSemanticsId, metricSemanticsId)
                        .orderByDesc(AiMetricSemanticsRevisionDO::getRevisionNo)
                        .last("limit 1"));
        if (existing.isEmpty()) {
            return 1L;
        }
        return existing.get(0).getRevisionNo() + 1;
    }

    private static String requireCode(String metricCode) {
        String text = trim(metricCode);
        if (text == null || !CODE_PATTERN.matcher(text).matches()) {
            throw exception(AI_REQUEST_INVALID);
        }
        return text;
    }

    private static void requireVersion(Integer version) {
        if (version == null || version < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static Long requireOperator() {
        Long operator = SecurityFrameworkUtils.getLoginUserId();
        if (operator == null) {
            // 没有操作员身份的调用不能登记口径（口径决定了"哪些数可以相加"）
            throw exception(AI_ACCESS_DENIED);
        }
        return operator;
    }

    private static LocalDateTime requireTime(String value) {
        LocalDateTime parsed = parseTime(value);
        if (parsed == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        return parsed;
    }

    private static LocalDateTime parseTime(String value) {
        String text = trim(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException invalid) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static String trimToEmpty(String value, int maxLength) {
        String text = trim(value);
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
