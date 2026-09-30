package com.basicframework.module.ai.service.semantic;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ACCESS_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_CATALOG_BUDGET_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_CODE_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_PUBLISHER_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_PUBLISHED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMappingMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectRevisionMapper;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingFacts;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingLine;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingProblem;
import com.basicframework.module.ai.domain.semantic.AiMasterObjectType;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingEntrySaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectSaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDetailDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDraftDTO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨系统主数据映射管理面实现（Y02）。
 *
 * <p>实现要点（每条都有负向测试）：
 * <ol>
 *   <li>发布前按**时间窗**检查冲突：同一对象同一系统同一实体类型下两条有效期重叠的源键，
 *       或同一源键与**其它对象当前已发布版本**的有效期重叠，都拒绝发布并给出冲突键；</li>
 *   <li>发布推进 {@code current_revision} 用对象乐观锁 CAS，发布本身用版本乐观锁 CAS，
 *       两步在同一事务里，任一步失败都不产生"版本已发布但对象没指向它"的中间态；</li>
 *   <li>草稿条目有登记预算（单版本 200 条），超预算拒绝而不是静默截断；</li>
 *   <li>条目只属于草稿：已发布版本上的增删一律拒绝（不可变），删除用版本 CAS。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiMasterObjectServiceImpl implements AiMasterObjectService {

    /** 对象标识：字母开头，字母数字与连字符/下划线，长度 3..64（与数据集标识同一口径）。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{2,63}$");

    /** 对象名称长度上限（与迁移列宽 128 对齐）。 */
    static final int MAX_NAME_LENGTH = 128;

    /** 说明长度上限（与迁移列宽 512 对齐）。 */
    static final int MAX_DESCRIPTION_LENGTH = 512;

    private final AiMasterObjectMapper objectMapper;

    private final AiMasterObjectRevisionMapper revisionMapper;

    private final AiMasterObjectMappingMapper mappingMapper;

    private final AiApplicationService applicationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createObject(AiMasterObjectSaveDTO saveDTO) {
        requireOperator();
        String objectCode = requireCode(saveDTO == null ? null : saveDTO.getObjectCode());
        String objectName = requireName(saveDTO == null ? null : saveDTO.getObjectName());
        String objectType = requireObjectType(saveDTO == null ? null : saveDTO.getObjectType());
        String description = normalizeDescription(saveDTO == null ? null : saveDTO.getDescription());
        if (objectMapper.selectByCode(objectCode) != null) {
            throw exception(AI_MASTER_OBJECT_CODE_DUPLICATE);
        }
        AiMasterObjectDO created = new AiMasterObjectDO()
                .setObjectCode(objectCode)
                .setObjectName(objectName)
                .setObjectType(objectType)
                .setDescription(description)
                .setStatus(AiMasterObjectDO.STATUS_ACTIVE)
                .setCurrentRevision(0L)
                .setVersion(0);
        objectMapper.insert(created);
        return created.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateObject(AiMasterObjectSaveDTO saveDTO) {
        requireOperator();
        if (saveDTO == null || saveDTO.getId() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        requireVersion(saveDTO.getVersion());
        AiMasterObjectDO existing = getObject(saveDTO.getId());
        // 标识不可修改：请求里给了不同标识直接拒绝（静默忽略会让调用方以为改成功了）
        if (saveDTO.getObjectCode() != null
                && !saveDTO.getObjectCode().isBlank()
                && !existing.getObjectCode().equals(saveDTO.getObjectCode().trim())) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiMasterObjectDO update = new AiMasterObjectDO()
                .setId(existing.getId())
                .setObjectName(requireName(saveDTO.getObjectName()))
                .setObjectType(requireObjectType(saveDTO.getObjectType()))
                .setDescription(normalizeDescription(saveDTO.getDescription()))
                .setVersion(saveDTO.getVersion() + 1);
        if (objectMapper.updateWithVersion(update, saveDTO.getVersion()) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateObjectStatus(Long id, Integer version, boolean enabled) {
        requireOperator();
        requireVersion(version);
        AiMasterObjectDO existing = getObject(id);
        String target = enabled ? AiMasterObjectDO.STATUS_ACTIVE : AiMasterObjectDO.STATUS_DISABLED;
        if (target.equals(existing.getStatus())) {
            // 幂等：目标状态已达成，不再消耗乐观锁版本
            return;
        }
        AiMasterObjectDO update =
                new AiMasterObjectDO().setId(existing.getId()).setStatus(target).setVersion(version + 1);
        if (objectMapper.updateWithVersion(update, version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public AiMasterObjectDO getObject(Long id) {
        AiMasterObjectDO object = id == null ? null : objectMapper.selectById(id);
        if (object == null) {
            throw exception(AI_MASTER_OBJECT_NOT_EXISTS);
        }
        return object;
    }

    @Override
    public AiMasterObjectDO getObjectByCode(String objectCode) {
        AiMasterObjectDO object = objectCode == null ? null : objectMapper.selectByCode(objectCode.trim());
        if (object == null) {
            throw exception(AI_MASTER_OBJECT_NOT_EXISTS);
        }
        return object;
    }

    @Override
    public PageResult<AiMasterObjectDO> getObjectPage(
            PageParam pageParam, String objectType, String status, String keyword) {
        if (pageParam == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (status != null
                && !status.isBlank()
                && !AiMasterObjectDO.STATUS_ACTIVE.equals(status)
                && !AiMasterObjectDO.STATUS_DISABLED.equals(status)) {
            throw exception(AI_REQUEST_INVALID);
        }
        String normalizedType = objectType == null || objectType.isBlank() ? null : requireObjectType(objectType);
        return objectMapper.selectPage(pageParam, normalizedType, status, keyword);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRevision(AiMasterRevisionDraftDTO draftDTO) {
        Long operator = requireOperator();
        if (draftDTO == null || draftDTO.getMasterObjectId() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiMasterObjectDO object = getObject(draftDTO.getMasterObjectId());
        LocalDateTime validFrom = draftDTO.getValidFrom();
        LocalDateTime validTo = draftDTO.getValidTo();
        if (validFrom == null || (validTo != null && !validTo.isAfter(validFrom))) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiMasterObjectRevisionDO latest = revisionMapper.selectLatest(object.getId());
        if (latest != null && AiMasterObjectRevisionDO.STATUS_DRAFT.equals(latest.getStatus())) {
            // 一个对象同时只允许一个未发布草稿：否则"条目加到哪个草稿"没有确定答案
            throw exception(AI_STATE_CONFLICT);
        }
        long revisionNo = latest == null ? 1L : latest.getRevisionNo() + 1;
        AiMasterObjectRevisionDO draft = new AiMasterObjectRevisionDO()
                .setMasterObjectId(object.getId())
                .setRevisionNo(revisionNo)
                .setStatus(AiMasterObjectRevisionDO.STATUS_DRAFT)
                .setValidFrom(validFrom)
                .setValidTo(validTo)
                .setEntryCount(0)
                .setMappingFingerprint("")
                .setCreatedBy(operator)
                .setVersion(0);
        revisionMapper.insert(draft);
        return revisionNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addMappingEntry(AiMasterMappingEntrySaveDTO saveDTO) {
        requireOperator();
        if (saveDTO == null || saveDTO.getMasterObjectId() == null || saveDTO.getRevisionNo() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        requireEnabledApplication(saveDTO.getApplicationId());
        AiMasterObjectRevisionDO revision = requireDraftRevision(saveDTO.getMasterObjectId(), saveDTO.getRevisionNo());
        if (mappingMapper.countByRevision(revision.getMasterObjectId(), revision.getRevisionNo())
                >= MAX_ENTRIES_PER_REVISION) {
            throw exception(AI_MASTER_OBJECT_CATALOG_BUDGET_EXCEEDED);
        }
        AiMasterMappingLine line = AiMasterMappingLine.of(
                revision.getMasterObjectId(),
                saveDTO.getApplicationId(),
                trim(saveDTO.getEntityType()),
                trim(saveDTO.getSourceKey()),
                saveDTO.getSourceName(),
                saveDTO.getMatchMethod(),
                saveDTO.getValidFrom(),
                saveDTO.getValidTo());
        if (mappingMapper.selectEntry(
                        line.masterObjectId(),
                        revision.getRevisionNo(),
                        line.applicationId(),
                        line.entityType(),
                        line.sourceKey())
                != null) {
            throw exception(AI_MASTER_MAPPING_ENTRY_DUPLICATE);
        }
        AiMasterObjectMappingDO entry = new AiMasterObjectMappingDO()
                .setMasterObjectId(line.masterObjectId())
                .setRevision(revision.getRevisionNo())
                .setApplicationId(line.applicationId())
                .setEntityType(line.entityType())
                .setSourceKey(line.sourceKey())
                .setSourceName(line.sourceName())
                .setMatchMethod(line.matchMethod())
                .setValidFrom(line.validFrom())
                .setValidTo(line.validTo())
                .setVersion(0);
        mappingMapper.insert(entry);
        return entry.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMappingEntry(Long entryId, Integer version) {
        requireOperator();
        requireVersion(version);
        AiMasterObjectMappingDO entry = entryId == null ? null : mappingMapper.selectById(entryId);
        if (entry == null) {
            throw exception(AI_MASTER_OBJECT_REVISION_NOT_EXISTS);
        }
        AiMasterObjectRevisionDO revision = requireDraftRevision(entry.getMasterObjectId(), entry.getRevision());
        if (!AiMasterObjectRevisionDO.STATUS_DRAFT.equals(revision.getStatus())) {
            throw exception(AI_MASTER_OBJECT_REVISION_PUBLISHED_CONFLICT);
        }
        if (mappingMapper.deleteEntry(entryId, version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMasterObjectRevisionDO publishRevision(Long masterObjectId, Long revisionNo, Integer version) {
        Long operator = requireOperator();
        requireVersion(version);
        AiMasterObjectDO object = getObject(masterObjectId);
        AiMasterObjectRevisionDO revision = getRevision(masterObjectId, revisionNo);
        if (!AiMasterObjectRevisionDO.STATUS_DRAFT.equals(revision.getStatus())) {
            throw exception(AI_STATE_CONFLICT);
        }
        if (operator.equals(revision.getCreatedBy())) {
            // 独立审核：草稿创建人不能自己发布
            throw exception(AI_MASTER_OBJECT_PUBLISHER_CONFLICT);
        }
        List<AiMasterObjectMappingDO> entries = mappingMapper.selectByRevision(object.getId(), revisionNo);
        if (entries.isEmpty()) {
            // 空版本没有可核验内容：与其发布一个"什么都没有"的版本，不如让登记继续
            throw exception(AI_MASTER_MAPPING_ENTRY_INVALID);
        }
        List<AiMasterMappingLine> lines =
                entries.stream().map(AiMasterObjectServiceImpl::toLine).toList();
        Map<String, List<AiMasterMappingLine>> conflicts =
                new LinkedHashMap<>(AiMasterMappingFacts.windowLineConflicts(lines));
        conflicts.putAll(crossObjectConflicts(object.getId(), lines));
        if (!conflicts.isEmpty()) {
            throw exception(AI_MASTER_MAPPING_CONFLICT, AiMasterMappingFacts.describeConflicts(conflicts));
        }
        String fingerprint = AiMasterMappingFacts.fingerprint(lines);
        AiMasterObjectRevisionDO publish = new AiMasterObjectRevisionDO()
                .setId(revision.getId())
                .setStatus(AiMasterObjectRevisionDO.STATUS_PUBLISHED)
                .setEntryCount(lines.size())
                .setMappingFingerprint(fingerprint)
                .setPublishedBy(operator)
                .setPublishedTime(LocalDateTime.now())
                .setVersion(version + 1);
        if (revisionMapper.updateWithVersion(publish, version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        AiMasterObjectDO advance = new AiMasterObjectDO()
                .setId(object.getId())
                .setCurrentRevision(revisionNo)
                .setVersion(object.getVersion() + 1);
        if (objectMapper.updateWithVersion(advance, object.getVersion()) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        return getRevision(masterObjectId, revisionNo);
    }

    @Override
    public AiMasterObjectRevisionDO getRevision(Long masterObjectId, Long revisionNo) {
        AiMasterObjectRevisionDO revision = masterObjectId == null || revisionNo == null
                ? null
                : revisionMapper.selectByRevisionNo(masterObjectId, revisionNo);
        if (revision == null) {
            throw exception(AI_MASTER_OBJECT_REVISION_NOT_EXISTS);
        }
        return revision;
    }

    @Override
    public PageResult<AiMasterObjectRevisionDO> getRevisionPage(
            PageParam pageParam, Long masterObjectId, String status) {
        if (pageParam == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (status != null
                && !status.isBlank()
                && !AiMasterObjectRevisionDO.STATUS_DRAFT.equals(status)
                && !AiMasterObjectRevisionDO.STATUS_PUBLISHED.equals(status)) {
            throw exception(AI_REQUEST_INVALID);
        }
        return revisionMapper.selectPage(pageParam, masterObjectId, status);
    }

    @Override
    public List<AiMasterObjectMappingDO> listEntries(Long masterObjectId, Long revisionNo) {
        getRevision(masterObjectId, revisionNo);
        return mappingMapper.selectByRevision(masterObjectId, revisionNo);
    }

    @Override
    public AiMasterRevisionDetailDTO getRevisionDetail(Long masterObjectId, Long revisionNo) {
        AiMasterObjectRevisionDO revision = getRevision(masterObjectId, revisionNo);
        List<AiMasterObjectMappingDO> entries = mappingMapper.selectByRevision(masterObjectId, revisionNo);
        List<AiMasterMappingLine> lines =
                entries.stream().map(AiMasterObjectServiceImpl::toLine).toList();
        Map<String, List<AiMasterMappingLine>> conflicts = AiMasterMappingFacts.windowLineConflicts(lines);
        conflicts.putAll(crossObjectConflicts(masterObjectId, lines));
        return new AiMasterRevisionDetailDTO()
                .setRevision(revision)
                .setEntries(entries)
                .setEntryProblems(entryProblems(entries, lines, conflicts))
                .setConflictKeys(new ArrayList<>(conflicts.keySet()))
                .setPublishable(AiMasterObjectRevisionDO.STATUS_DRAFT.equals(revision.getStatus())
                        && !entries.isEmpty()
                        && conflicts.isEmpty());
    }

    /**
     * 编辑期问题预览：冲突优先于过期（冲突是结构问题，改写窗口也躲不开；过期只是时间问题）。
     *
     * <p>"过期"在这里按读取时刻判断，仅供编辑参考；判定路径一律使用调用方给出的判定时刻。
     */
    private static Map<Long, String> entryProblems(
            List<AiMasterObjectMappingDO> entries,
            List<AiMasterMappingLine> lines,
            Map<String, List<AiMasterMappingLine>> conflicts) {
        Map<Long, String> problems = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            AiMasterMappingLine line = lines.get(index);
            String problem;
            if (conflicts.containsKey(line.objectSystemIdentity()) || conflicts.containsKey(line.sourceIdentity())) {
                problem = AiMasterMappingProblem.CONFLICT.name();
            } else if (!line.inForceAt(LocalDateTime.now())) {
                problem = AiMasterMappingProblem.EXPIRED.name();
            } else {
                problem = AiMasterMappingProblem.NONE.name();
            }
            problems.put(entries.get(index).getId(), problem);
        }
        return problems;
    }

    /** 草稿版本的强制读取：不存在抛 404，已发布抛"不可编辑"。 */
    private AiMasterObjectRevisionDO requireDraftRevision(Long masterObjectId, Long revisionNo) {
        AiMasterObjectRevisionDO revision = getRevision(masterObjectId, revisionNo);
        if (!AiMasterObjectRevisionDO.STATUS_DRAFT.equals(revision.getStatus())) {
            throw exception(AI_MASTER_OBJECT_REVISION_PUBLISHED_CONFLICT);
        }
        return revision;
    }

    /** 与其它对象当前已发布版本的冲突：同一源键在重叠时间窗内属于多个对象。 */
    private Map<String, List<AiMasterMappingLine>> crossObjectConflicts(
            Long masterObjectId, List<AiMasterMappingLine> lines) {
        Map<String, List<AiMasterMappingLine>> conflicts = new LinkedHashMap<>();
        for (AiMasterMappingLine line : lines) {
            List<AiMasterMappingLine> others =
                    mappingMapper
                            .selectCurrentBySourceKey(line.applicationId(), line.entityType(), line.sourceKey())
                            .stream()
                            .filter(row -> !masterObjectId.equals(row.getMasterObjectId()))
                            .map(AiMasterObjectServiceImpl::toLine)
                            .filter(other -> AiMasterMappingFacts.overlaps(line, other))
                            .toList();
            if (!others.isEmpty()) {
                List<AiMasterMappingLine> group = new ArrayList<>(others);
                group.add(line);
                conflicts.put(line.sourceIdentity(), group);
            }
        }
        return conflicts;
    }

    private void requireEnabledApplication(Long applicationId) {
        if (applicationId == null || applicationId <= 0) {
            throw exception(AI_MASTER_MAPPING_ENTRY_INVALID);
        }
        AiApplicationDO application;
        try {
            application = applicationService.getApplication(applicationId);
        } catch (com.basicframework.framework.common.exception.ServiceException notExists) {
            // 来源系统不存在与"来源系统已停用"同语义：都不是可登记的映射来源
            throw exception(AI_MASTER_MAPPING_ENTRY_INVALID);
        }
        if (application == null || !Boolean.TRUE.equals(application.getEnabled())) {
            throw exception(AI_MASTER_MAPPING_ENTRY_INVALID);
        }
    }

    private static String requireCode(String objectCode) {
        if (objectCode == null || !CODE_PATTERN.matcher(objectCode.trim()).matches()) {
            throw exception(AI_REQUEST_INVALID);
        }
        return objectCode.trim();
    }

    private static String requireName(String objectName) {
        if (objectName == null || objectName.isBlank() || objectName.trim().length() > MAX_NAME_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        return objectName.trim();
    }

    private static String requireObjectType(String objectType) {
        return AiMasterObjectType.parse(objectType).map(Enum::name).orElseThrow(() -> exception(AI_REQUEST_INVALID));
    }

    private static String normalizeDescription(String description) {
        String normalized = description == null ? "" : description.trim();
        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        return normalized;
    }

    private static void requireVersion(Integer version) {
        if (version == null || version < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static Long requireOperator() {
        Long operator = SecurityFrameworkUtils.getLoginUserId();
        if (operator == null) {
            // 没有操作员身份的调用不能登记主数据映射（映射决定了"哪些标识算同一实体"）
            throw exception(AI_ACCESS_DENIED);
        }
        return operator;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** DAL 行 → domain 事实（判定与指纹都只认 domain 事实）。 */
    static AiMasterMappingLine toLine(AiMasterObjectMappingDO row) {
        return new AiMasterMappingLine(
                row.getMasterObjectId(),
                row.getApplicationId(),
                row.getEntityType(),
                row.getSourceKey(),
                row.getSourceName(),
                row.getMatchMethod(),
                row.getValidFrom(),
                row.getValidTo());
    }
}
