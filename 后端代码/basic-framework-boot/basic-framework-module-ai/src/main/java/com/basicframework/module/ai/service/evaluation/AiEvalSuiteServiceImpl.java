package com.basicframework.module.ai.service.evaluation;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_CASE_KEY_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_CASE_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_CHECK_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_DATA_LEVEL_NOT_ALLOWED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_SUITE_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_SUITE_FROZEN;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_SUITE_HAS_NO_CASE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_SUITE_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_EVAL_SUITE_NOT_FROZEN;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.mysql.evaluation.AiEvalCaseMapper;
import com.basicframework.module.ai.dal.mysql.evaluation.AiEvalSuiteMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceMapper;
import com.basicframework.module.ai.service.evaluation.dto.AiEvalCaseSaveDTO;
import com.basicframework.module.ai.service.evaluation.dto.AiEvalSuiteSaveDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 评测套件与样例实现（Q04）。 */
@Service
@RequiredArgsConstructor
public class AiEvalSuiteServiceImpl implements AiEvalSuiteService {

    /** 允许的样例数据分级：评测夹具只能是合成数据。 */
    private static final Set<String> ALLOWED_LEVELS = Set.of(AiEvalSuiteDO.LEVEL_PUBLIC, AiEvalSuiteDO.LEVEL_INTERNAL);

    private static final Set<String> SUBJECT_TYPES = Set.of("APP", "USER");

    private static final Set<String> SEVERITIES =
            Set.of(AiEvalCaseDO.SEVERITY_BLOCKER, AiEvalCaseDO.SEVERITY_MAJOR, AiEvalCaseDO.SEVERITY_MINOR);

    private final AiEvalSuiteMapper suiteMapper;

    private final AiEvalCaseMapper caseMapper;

    private final AiServiceMapper serviceMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createSuite(AiEvalSuiteSaveDTO saveDTO) {
        requireSuiteFields(saveDTO);
        if (suiteMapper.selectByCode(saveDTO.getApplicationId(), saveDTO.getCode()) != null) {
            throw exception(AI_EVAL_SUITE_CODE_DUPLICATE);
        }
        AiEvalSuiteDO entity = new AiEvalSuiteDO()
                .setApplicationId(saveDTO.getApplicationId())
                .setCode(saveDTO.getCode())
                .setName(saveDTO.getName())
                .setDescription(saveDTO.getDescription())
                .setServiceId(saveDTO.getServiceId())
                .setSubjectType(saveDTO.getSubjectType())
                .setExternalUserId(
                        StringUtils.hasText(saveDTO.getExternalUserId()) ? saveDTO.getExternalUserId() : "eval-runner")
                .setDataLevel(defaultLevel(saveDTO.getDataLevel()))
                .setStatus(AiEvalSuiteDO.STATUS_DRAFT)
                .setRevision(1)
                .setCaseCount(0)
                .setVersion(0);
        suiteMapper.insert(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSuite(AiEvalSuiteSaveDTO saveDTO) {
        AiEvalSuiteDO suite = requireSuite(saveDTO.getId());
        requireDraft(suite);
        if (saveDTO.getName() == null || saveDTO.getName().isBlank()) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (saveDTO.getDataLevel() != null && !ALLOWED_LEVELS.contains(saveDTO.getDataLevel())) {
            throw exception(AI_EVAL_DATA_LEVEL_NOT_ALLOWED);
        }
        int current = suite.getVersion() == null ? 0 : suite.getVersion();
        if (!Objects.equals(saveDTO.getVersion(), current)) {
            throw exception(AI_STATE_CONFLICT);
        }
        if (suiteMapper.updateWithVersion(
                        new AiEvalSuiteDO()
                                .setId(suite.getId())
                                .setName(saveDTO.getName())
                                .setDescription(saveDTO.getDescription())
                                .setDataLevel(
                                        saveDTO.getDataLevel() == null ? suite.getDataLevel() : saveDTO.getDataLevel())
                                .setVersion(current + 1),
                        current)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void freezeSuite(Long suiteId, Integer version) {
        AiEvalSuiteDO suite = requireSuite(suiteId);
        int current = suite.getVersion() == null ? 0 : suite.getVersion();
        if (!Objects.equals(version, current)) {
            throw exception(AI_STATE_CONFLICT);
        }
        List<AiEvalCaseDO> cases = listCases(suiteId);
        if (cases.isEmpty()) {
            throw exception(AI_EVAL_SUITE_HAS_NO_CASE);
        }
        // 冻结即校验：规则不合规的套件不允许成为"已冻结的基线"
        for (AiEvalCaseDO item : cases) {
            requireRules(item.getChecksJson());
        }
        int revision = suite.getRevision() == null ? 1 : suite.getRevision();
        if (suiteMapper.updateWithVersion(
                        new AiEvalSuiteDO()
                                .setId(suite.getId())
                                .setStatus(AiEvalSuiteDO.STATUS_FROZEN)
                                .setRevision(revision + 1)
                                .setCaseCount(cases.size())
                                .setFrozenTime(LocalDateTime.now())
                                .setContentDigest(AiEvalSuiteDigests.of(suite, cases))
                                .setVersion(current + 1),
                        current)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void newRevision(Long suiteId, Integer version) {
        AiEvalSuiteDO suite = requireSuite(suiteId);
        if (!AiEvalSuiteDO.STATUS_FROZEN.equals(suite.getStatus())) {
            throw exception(AI_EVAL_SUITE_NOT_FROZEN);
        }
        int current = suite.getVersion() == null ? 0 : suite.getVersion();
        if (!Objects.equals(version, current)) {
            throw exception(AI_STATE_CONFLICT);
        }
        int revision = suite.getRevision() == null ? 1 : suite.getRevision();
        if (suiteMapper.updateWithVersion(
                        new AiEvalSuiteDO()
                                .setId(suite.getId())
                                .setStatus(AiEvalSuiteDO.STATUS_DRAFT)
                                .setRevision(revision + 1)
                                .setVersion(current + 1),
                        current)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public AiEvalSuiteDO requireSuite(Long suiteId) {
        AiEvalSuiteDO suite = suiteId == null ? null : suiteMapper.selectById(suiteId);
        if (suite == null) {
            throw exception(AI_EVAL_SUITE_NOT_EXISTS);
        }
        return suite;
    }

    @Override
    public PageResult<AiEvalSuiteDO> pageSuites(PageParam pageParam, Long applicationId, String status) {
        if (status != null
                && !AiEvalSuiteDO.STATUS_DRAFT.equals(status)
                && !AiEvalSuiteDO.STATUS_FROZEN.equals(status)) {
            throw exception(AI_REQUEST_INVALID);
        }
        return suiteMapper.selectPage(pageParam, applicationId, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCase(AiEvalCaseSaveDTO saveDTO) {
        AiEvalSuiteDO suite = requireSuite(saveDTO.getSuiteId());
        requireDraft(suite);
        requireCaseFields(saveDTO);
        if (caseMapper.selectByCaseKey(suite.getId(), saveDTO.getCaseKey()) != null) {
            throw exception(AI_EVAL_CASE_KEY_DUPLICATE);
        }
        AiEvalCaseDO entity = new AiEvalCaseDO()
                .setSuiteId(suite.getId())
                .setCaseKey(saveDTO.getCaseKey())
                .setTitle(saveDTO.getTitle())
                .setSeverity(defaultSeverity(saveDTO.getSeverity()))
                .setQuestion(saveDTO.getQuestion())
                .setExpectVersion(saveDTO.getExpectVersion())
                .setChecksJson(AiEvalDigest.canonicalJsonText(saveDTO.getChecksJson()))
                .setNeedsReview(Boolean.TRUE.equals(saveDTO.getNeedsReview()))
                .setVersion(0);
        caseMapper.insert(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCase(AiEvalCaseSaveDTO saveDTO) {
        AiEvalCaseDO existing = requireCase(saveDTO.getId());
        AiEvalSuiteDO suite = requireSuite(existing.getSuiteId());
        requireDraft(suite);
        if (saveDTO.getTitle() == null
                || saveDTO.getTitle().isBlank()
                || saveDTO.getQuestion() == null
                || saveDTO.getQuestion().isBlank()) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (saveDTO.getSeverity() != null && !SEVERITIES.contains(saveDTO.getSeverity())) {
            throw exception(AI_REQUEST_INVALID);
        }
        int current = existing.getVersion() == null ? 0 : existing.getVersion();
        if (!Objects.equals(saveDTO.getVersion(), current)) {
            throw exception(AI_STATE_CONFLICT);
        }
        if (caseMapper.updateWithVersion(
                        new AiEvalCaseDO()
                                .setId(existing.getId())
                                .setTitle(saveDTO.getTitle())
                                .setSeverity(
                                        saveDTO.getSeverity() == null ? existing.getSeverity() : saveDTO.getSeverity())
                                .setQuestion(saveDTO.getQuestion())
                                .setExpectVersion(saveDTO.getExpectVersion())
                                .setChecksJson(
                                        saveDTO.getChecksJson() == null
                                                ? existing.getChecksJson()
                                                : AiEvalDigest.canonicalJsonText(saveDTO.getChecksJson()))
                                .setNeedsReview(
                                        saveDTO.getNeedsReview() == null
                                                ? existing.getNeedsReview()
                                                : saveDTO.getNeedsReview())
                                .setVersion(current + 1),
                        current)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        if (saveDTO.getChecksJson() != null) {
            requireRules(saveDTO.getChecksJson());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCase(Long caseId, Integer version) {
        AiEvalCaseDO existing = requireCase(caseId);
        AiEvalSuiteDO suite = requireSuite(existing.getSuiteId());
        requireDraft(suite);
        int current = existing.getVersion() == null ? 0 : existing.getVersion();
        if (!Objects.equals(version, current)) {
            throw exception(AI_STATE_CONFLICT);
        }
        caseMapper.deleteById(caseId);
    }

    @Override
    public AiEvalCaseDO requireCase(Long caseId) {
        AiEvalCaseDO entity = caseId == null ? null : caseMapper.selectById(caseId);
        if (entity == null) {
            throw exception(AI_EVAL_CASE_NOT_EXISTS);
        }
        return entity;
    }

    @Override
    public List<AiEvalCaseDO> listCases(Long suiteId) {
        requireSuite(suiteId);
        return caseMapper.selectBySuite(suiteId);
    }

    private void requireSuiteFields(AiEvalSuiteSaveDTO saveDTO) {
        if (saveDTO == null
                || saveDTO.getApplicationId() == null
                || !StringUtils.hasText(saveDTO.getCode())
                || !StringUtils.hasText(saveDTO.getName())
                || saveDTO.getServiceId() == null
                || !StringUtils.hasText(saveDTO.getSubjectType())
                || !SUBJECT_TYPES.contains(saveDTO.getSubjectType())) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (saveDTO.getDataLevel() != null && !ALLOWED_LEVELS.contains(saveDTO.getDataLevel())) {
            throw exception(AI_EVAL_DATA_LEVEL_NOT_ALLOWED);
        }
        // 被评测服务必须属于同一应用：跨应用评测等于跨租户取数，直接拒绝
        AiServiceDO service = serviceMapper.selectById(saveDTO.getServiceId());
        if (service == null || !Objects.equals(service.getAppId(), saveDTO.getApplicationId())) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private void requireCaseFields(AiEvalCaseSaveDTO saveDTO) {
        if (saveDTO == null
                || !StringUtils.hasText(saveDTO.getCaseKey())
                || !StringUtils.hasText(saveDTO.getTitle())
                || !StringUtils.hasText(saveDTO.getQuestion())
                || !StringUtils.hasText(saveDTO.getChecksJson())) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (saveDTO.getSeverity() != null && !SEVERITIES.contains(saveDTO.getSeverity())) {
            throw exception(AI_REQUEST_INVALID);
        }
        requireRules(saveDTO.getChecksJson());
    }

    /** 期望规则必须能被检查器解析（否则样例入库就会在评测时变成 ERROR）。 */
    private void requireRules(String checksJson) {
        try {
            AiEvalChecks.validateRules(checksJson);
        } catch (IllegalArgumentException exception) {
            throw exception(AI_EVAL_CHECK_INVALID);
        }
    }

    private void requireDraft(AiEvalSuiteDO suite) {
        if (!AiEvalSuiteDO.STATUS_DRAFT.equals(suite.getStatus())) {
            throw exception(AI_EVAL_SUITE_FROZEN);
        }
    }

    private static String defaultLevel(String dataLevel) {
        return StringUtils.hasText(dataLevel) ? dataLevel : AiEvalSuiteDO.LEVEL_INTERNAL;
    }

    private static String defaultSeverity(String severity) {
        return StringUtils.hasText(severity) ? severity : AiEvalCaseDO.SEVERITY_MAJOR;
    }
}
