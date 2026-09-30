package com.basicframework.module.ai.service.semantic;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_CATALOG_BUDGET_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_DISABLED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMapper;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingFacts;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingLine;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingProblem;
import com.basicframework.module.ai.service.application.AiSystemCatalogService;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogQueryDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemEntryDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterCatalogEntryDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogQueryDTO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 主数据映射目录发现实现（Y02）。
 *
 * <p>可访问系统集合**复用 Y01 的授权发现**（{@link AiSystemCatalogService}），不另建一套"谁能看哪个系统"
 * 的判断：主体的范围、授权与跨系统身份联邦事实全部由 Y01 的目录给出，本服务只做交集——
 * 无权系统里的映射条目既不出现在条目列表，也不出现在送进模型的目录里。
 *
 * <p>目录指纹由"身份 + 对象 + 版本指纹 + 可见条目事实"构成：任何一项变化（换版本、加权限、
 * 条目被改）都会改变指纹，因此它可以被调用方当作"这次看到的映射事实"的凭据固定下来。
 */
@Service
@RequiredArgsConstructor
public class AiMasterObjectCatalogServiceImpl implements AiMasterObjectCatalogService {

    /** 目录可见条目预算（与单版本登记预算同一口径）：超过即拒绝，不返回部分目录。 */
    public static final int MAX_VISIBLE_ENTRIES = AiMasterObjectService.MAX_ENTRIES_PER_REVISION;

    private final AiMasterObjectMapper objectMapper;

    private final AiMasterRevisionVerifier revisionVerifier;

    private final AiSystemCatalogService systemCatalogService;

    @Override
    public AiMasterObjectCatalogDTO discover(AiMasterObjectCatalogQueryDTO queryDTO) {
        requireQuery(queryDTO);
        AiMasterObjectDO object =
                objectMapper.selectByCode(queryDTO.getObjectCode().trim());
        if (object == null) {
            throw exception(AI_MASTER_OBJECT_NOT_EXISTS);
        }
        if (!AiMasterObjectDO.STATUS_ACTIVE.equals(object.getStatus())) {
            throw exception(AI_MASTER_OBJECT_DISABLED_CONFLICT);
        }
        AiMasterRevisionVerifier.VerifiedRevision verified =
                revisionVerifier.verify(object.getId(), queryDTO.getRevisionNo(), queryDTO.getAsOf());
        if (verified.lines().size() > MAX_VISIBLE_ENTRIES) {
            // 防御性读取预算：登记侧已限制 200 条，这里保证"任何情况下都不返回部分目录"
            throw exception(AI_MASTER_OBJECT_CATALOG_BUDGET_EXCEEDED);
        }
        AiSystemCatalogDTO systems = systemCatalogService.discover(new AiSystemCatalogQueryDTO()
                .setApplicationId(queryDTO.getApplicationId())
                .setSubjectType(queryDTO.getSubjectType())
                .setExternalUserId(queryDTO.getExternalUserId()));

        AiMasterObjectCatalogDTO catalog = new AiMasterObjectCatalogDTO()
                .setMasterObjectId(object.getId())
                .setObjectCode(object.getObjectCode())
                .setObjectName(object.getObjectName())
                .setObjectType(object.getObjectType())
                .setRevisionNo(verified.revision().getRevisionNo())
                .setRevisionFingerprint(verified.revision().getMappingFingerprint())
                .setAsOf(queryDTO.getAsOf())
                .setDenied(systems.isDenied());
        if (systems.isDenied()) {
            // 拒绝不可区分：条目为空 + 与"从未登记"完全相同的指纹形态（只由身份与对象版本决定）
            return catalog.setEntries(List.of())
                    .setCatalogFingerprint(digest(queryDTO, verified, List.of()))
                    .setModelCatalog("[]");
        }

        Map<Long, AiSystemEntryDTO> accessible = systems.getEntries().stream()
                .collect(Collectors.toMap(AiSystemEntryDTO::getApplicationId, entry -> entry, (left, right) -> left));
        List<AiMasterMappingLine> visible = verified.lines().stream()
                .filter(line -> accessible.containsKey(line.applicationId()))
                .toList();
        if (visible.size() > MAX_VISIBLE_ENTRIES) {
            throw exception(AI_MASTER_OBJECT_CATALOG_BUDGET_EXCEEDED);
        }
        Map<String, List<AiMasterMappingLine>> lineConflicts =
                AiMasterMappingFacts.instantLineConflicts(verified.lines(), queryDTO.getAsOf());
        Map<String, List<AiMasterMappingLine>> objectConflicts =
                AiMasterMappingFacts.instantObjectConflicts(verified.lines(), queryDTO.getAsOf());
        List<AiMasterCatalogEntryDTO> entries = new ArrayList<>();
        for (AiMasterMappingLine line : visible) {
            AiSystemEntryDTO system = accessible.get(line.applicationId());
            entries.add(toEntry(line, system, queryDTO.getAsOf(), lineConflicts, objectConflicts));
        }
        return catalog.setEntries(entries)
                .setCatalogFingerprint(digest(queryDTO, verified, entries))
                .setModelCatalog(modelCatalog(object, verified, entries));
    }

    private static AiMasterCatalogEntryDTO toEntry(
            AiMasterMappingLine line,
            AiSystemEntryDTO system,
            LocalDateTime asOf,
            Map<String, List<AiMasterMappingLine>> lineConflicts,
            Map<String, List<AiMasterMappingLine>> objectConflicts) {
        boolean inForce = line.inForceAt(asOf);
        AiMasterMappingProblem problem = AiMasterMappingProblem.NONE;
        if (!inForce) {
            problem = AiMasterMappingProblem.EXPIRED;
        } else if (lineConflicts.containsKey(line.objectSystemIdentity())
                || objectConflicts.containsKey(line.sourceIdentity())) {
            problem = AiMasterMappingProblem.CONFLICT;
        }
        return new AiMasterCatalogEntryDTO()
                .setApplicationId(line.applicationId())
                .setAppCode(system.getAppCode())
                .setSystemName(system.getSystemName())
                .setEntityType(line.entityType())
                .setSourceKey(line.sourceKey())
                .setSourceName(line.sourceName())
                .setMatchMethod(line.matchMethod())
                .setValidFrom(line.validFrom())
                .setValidTo(line.validTo())
                .setInForce(inForce)
                .setUsable(problem == AiMasterMappingProblem.NONE)
                .setProblem(problem.name());
    }

    /**
     * 模型可见目录：只含对象、系统、实体类型、匹配方式与可用性。
     *
     * <p>**不含源键值**——键值是业务数据，留在服务端参与关联执行；模型只需要知道"哪些系统之间可以做
     * 这个对象的关联、是否可用"，据此决定能不能规划跨系统查询（Y03）。
     */
    private static String modelCatalog(
            AiMasterObjectDO object,
            AiMasterRevisionVerifier.VerifiedRevision verified,
            List<AiMasterCatalogEntryDTO> entries) {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("objectCode", object.getObjectCode());
        catalog.put("objectType", object.getObjectType());
        catalog.put("revisionNo", verified.revision().getRevisionNo());
        List<Object> systems = new ArrayList<>();
        for (AiMasterCatalogEntryDTO entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("appCode", entry.getAppCode());
            row.put("entityType", entry.getEntityType());
            row.put("matchMethod", entry.getMatchMethod());
            row.put("usable", entry.isUsable());
            row.put("problem", entry.getProblem());
            systems.add(row);
        }
        catalog.put("systems", systems);
        return JsonUtils.toJsonString(catalog);
    }

    /** 目录指纹：身份 + 对象版本指纹 + 可见条目事实（条目为空时也稳定）。 */
    private static String digest(
            AiMasterObjectCatalogQueryDTO queryDTO,
            AiMasterRevisionVerifier.VerifiedRevision verified,
            List<AiMasterCatalogEntryDTO> entries) {
        Set<String> entryFacts = entries.stream()
                .map(entry -> entry.getApplicationId() + "|" + entry.getEntityType() + "|" + entry.getSourceKey() + "|"
                        + entry.getMatchMethod() + "|" + entry.getProblem() + "|" + entry.getValidFrom() + "|"
                        + entry.getValidTo())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        return AiMasterMappingFacts.digest(String.join(
                "\n",
                String.valueOf(queryDTO.getApplicationId()),
                String.valueOf(queryDTO.getSubjectType()),
                String.valueOf(queryDTO.getExternalUserId()),
                queryDTO.getObjectCode().trim(),
                String.valueOf(verified.revision().getRevisionNo()),
                verified.revision().getMappingFingerprint(),
                String.join(";", entryFacts)));
    }

    private static void requireQuery(AiMasterObjectCatalogQueryDTO queryDTO) {
        if (queryDTO == null
                || queryDTO.getObjectCode() == null
                || queryDTO.getObjectCode().isBlank()
                || queryDTO.getRevisionNo() == null
                || queryDTO.getRevisionNo() <= 0
                || queryDTO.getApplicationId() == null
                || queryDTO.getApplicationId() <= 0
                || queryDTO.getSubjectType() == null
                || queryDTO.getSubjectType().isBlank()
                || queryDTO.getAsOf() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
