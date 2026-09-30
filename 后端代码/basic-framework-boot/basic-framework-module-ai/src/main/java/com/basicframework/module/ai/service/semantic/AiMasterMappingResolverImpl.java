package com.basicframework.module.ai.service.semantic;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_MAPPING_EXPIRED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_MAPPING_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_DISABLED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MASTER_OBJECT_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMappingMapper;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingFacts;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingLine;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolutionDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseResultDTO;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 主数据映射判定实现（Y02）。
 *
 * <p>判定只有三种结论：唯一命中、未登记（{@code mapped=false}）、阻断（稳定错误码）。实现上刻意
 * 保留三道"多余"的检查，因为它们各自对应一种真实的失真来源：
 * <ol>
 *   <li><b>版本指纹重算</b>：版本发布后被版本外改动（直连数据库改源键）时，历史报表不能按被改过的
 *       内容解释，必须阻断；</li>
 *   <li><b>多对一复核</b>：发布时已拦截同源键属于多个对象，判定侧仍复核一次——冲突一旦存在
 *       （历史数据、人工修库），宁可拒绝也不能挑一个；</li>
 *   <li><b>对象当前版本一致性</b>：反查路径先取"当前已发布版本"的行，再读对象确认仍是该版本；
 *       两次读取之间版本被推进时拒绝（事实在读取过程中变化）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiMasterMappingResolverImpl implements AiMasterMappingResolver {

    /** 未命中原因：该源键没有任何已发布的映射（未映射即不关联）。 */
    public static final String REASON_NOT_REGISTERED = "NOT_REGISTERED";

    private final AiMasterObjectMapper objectMapper;

    private final AiMasterObjectMappingMapper mappingMapper;

    private final AiMasterRevisionVerifier revisionVerifier;

    @Override
    public AiMasterMappingResolutionDTO resolveObjectKey(AiMasterMappingResolveDTO resolveDTO) {
        requireResolveRequest(resolveDTO);
        AiMasterObjectDO object = requireActiveObject(resolveDTO.getObjectCode());
        AiMasterRevisionVerifier.VerifiedRevision verified =
                revisionVerifier.verify(object.getId(), resolveDTO.getRevisionNo(), resolveDTO.getAsOf());
        String entityType = resolveDTO.getEntityType().trim();
        List<AiMasterMappingLine> registered = verified.lines().stream()
                .filter(line -> resolveDTO.getApplicationId().equals(line.applicationId()))
                .filter(line -> entityType.equals(line.entityType()))
                .toList();
        if (registered.isEmpty()) {
            // 该对象在这个系统里没有登记任何源键：未映射（调用方据此拒绝跨系统合并）
            throw exception(AI_MASTER_MAPPING_NOT_EXISTS);
        }
        List<AiMasterMappingLine> inForce = AiMasterMappingFacts.inForceLines(registered, resolveDTO.getAsOf());
        if (inForce.isEmpty()) {
            // 有登记但有效期都不覆盖判定时刻：过期（或尚未生效）必须阻断，不能拿"没有可用映射"糊过去
            throw exception(AI_MASTER_MAPPING_EXPIRED_CONFLICT);
        }
        if (inForce.size() > 1) {
            throw exception(
                    AI_MASTER_MAPPING_CONFLICT,
                    AiMasterMappingFacts.describeConflicts(
                            AiMasterMappingFacts.instantLineConflicts(registered, resolveDTO.getAsOf())));
        }
        AiMasterMappingLine line = inForce.get(0);
        requireNoCrossObjectConflict(line, resolveDTO.getAsOf());
        return new AiMasterMappingResolutionDTO()
                .setMasterObjectId(object.getId())
                .setObjectCode(object.getObjectCode())
                .setObjectName(object.getObjectName())
                .setObjectType(object.getObjectType())
                .setRevisionNo(verified.revision().getRevisionNo())
                .setRevisionFingerprint(verified.revision().getMappingFingerprint())
                .setAsOf(resolveDTO.getAsOf())
                .setApplicationId(line.applicationId())
                .setEntityType(line.entityType())
                .setSourceKey(line.sourceKey())
                .setSourceName(line.sourceName())
                .setMatchMethod(line.matchMethod())
                .setValidFrom(line.validFrom())
                .setValidTo(line.validTo());
    }

    @Override
    public AiMasterMappingReverseResultDTO resolveSourceKey(AiMasterMappingReverseDTO reverseDTO) {
        requireReverseRequest(reverseDTO);
        String entityType = reverseDTO.getEntityType().trim();
        String sourceKey = reverseDTO.getSourceKey().trim();
        List<AiMasterObjectMappingDO> rows =
                mappingMapper.selectCurrentBySourceKey(reverseDTO.getApplicationId(), entityType, sourceKey);
        if (rows.isEmpty()) {
            // 未登记：唯一"不报错"的否定结论，调用方据此拒绝把两条记录当成同一实体
            return new AiMasterMappingReverseResultDTO()
                    .setMapped(false)
                    .setReason(REASON_NOT_REGISTERED)
                    .setApplicationId(reverseDTO.getApplicationId())
                    .setEntityType(entityType)
                    .setSourceKey(sourceKey)
                    .setAsOf(reverseDTO.getAsOf());
        }
        List<AiMasterMappingLine> lines =
                rows.stream().map(AiMasterObjectServiceImpl::toLine).toList();
        List<AiMasterObjectMappingDO> inForceRows = rows.stream()
                .filter(row -> AiMasterMappingFacts.inForce(row.getValidFrom(), row.getValidTo(), reverseDTO.getAsOf()))
                .toList();
        if (inForceRows.isEmpty()) {
            throw exception(AI_MASTER_MAPPING_EXPIRED_CONFLICT);
        }
        if (inForceRows.stream()
                        .map(AiMasterObjectMappingDO::getMasterObjectId)
                        .distinct()
                        .count()
                > 1) {
            throw exception(
                    AI_MASTER_MAPPING_CONFLICT,
                    AiMasterMappingFacts.describeConflicts(
                            AiMasterMappingFacts.instantObjectConflicts(lines, reverseDTO.getAsOf())));
        }
        AiMasterObjectMappingDO matched = inForceRows.get(0);
        AiMasterMappingLine line = AiMasterObjectServiceImpl.toLine(matched);
        AiMasterObjectDO object = objectMapper.selectById(line.masterObjectId());
        if (object == null) {
            // 映射行存在但对象已删除：没有可解释的归属，按"未登记"表达（不猜测）
            throw exception(AI_MASTER_MAPPING_NOT_EXISTS);
        }
        if (!AiMasterObjectDO.STATUS_ACTIVE.equals(object.getStatus())) {
            throw exception(AI_MASTER_OBJECT_DISABLED_CONFLICT);
        }
        if (!matched.getRevision().equals(object.getCurrentRevision())) {
            // 读取过程中对象当前版本被推进：两次读取的事实不一致，拒绝解释
            throw exception(AI_STATE_CONFLICT);
        }
        AiMasterRevisionVerifier.VerifiedRevision verified =
                revisionVerifier.verify(object.getId(), matched.getRevision(), reverseDTO.getAsOf());
        boolean stillRegistered =
                verified.lines().stream().anyMatch(fact -> fact.sourceIdentity().equals(line.sourceIdentity()));
        if (!stillRegistered) {
            // 当前版本内容里已经没有这条源键（与候选行矛盾）：事实不一致，按未登记表达
            throw exception(AI_MASTER_MAPPING_NOT_EXISTS);
        }
        return new AiMasterMappingReverseResultDTO()
                .setMapped(true)
                .setApplicationId(reverseDTO.getApplicationId())
                .setEntityType(entityType)
                .setSourceKey(sourceKey)
                .setAsOf(reverseDTO.getAsOf())
                .setMasterObjectId(object.getId())
                .setObjectCode(object.getObjectCode())
                .setObjectName(object.getObjectName())
                .setObjectType(object.getObjectType())
                .setRevisionNo(verified.revision().getRevisionNo())
                .setRevisionFingerprint(verified.revision().getMappingFingerprint())
                .setMatchMethod(line.matchMethod())
                .setSourceName(line.sourceName())
                .setValidFrom(line.validFrom())
                .setValidTo(line.validTo());
    }

    /** 判定侧复核多对一：同一源键在判定时刻是否同时属于别的对象。 */
    private void requireNoCrossObjectConflict(AiMasterMappingLine line, LocalDateTime asOf) {
        List<AiMasterMappingLine> others =
                mappingMapper
                        .selectCurrentBySourceKey(line.applicationId(), line.entityType(), line.sourceKey())
                        .stream()
                        .filter(row -> !row.getMasterObjectId().equals(line.masterObjectId()))
                        .map(AiMasterObjectServiceImpl::toLine)
                        .filter(other -> other.inForceAt(asOf))
                        .toList();
        if (!others.isEmpty()) {
            throw exception(AI_MASTER_MAPPING_CONFLICT, line.sourceIdentity());
        }
    }

    private AiMasterObjectDO requireActiveObject(String objectCode) {
        AiMasterObjectDO object = objectCode == null ? null : objectMapper.selectByCode(objectCode.trim());
        if (object == null) {
            throw exception(AI_MASTER_OBJECT_NOT_EXISTS);
        }
        if (!AiMasterObjectDO.STATUS_ACTIVE.equals(object.getStatus())) {
            throw exception(AI_MASTER_OBJECT_DISABLED_CONFLICT);
        }
        return object;
    }

    private static void requireResolveRequest(AiMasterMappingResolveDTO resolveDTO) {
        if (resolveDTO == null
                || resolveDTO.getObjectCode() == null
                || resolveDTO.getObjectCode().isBlank()
                || resolveDTO.getRevisionNo() == null
                || resolveDTO.getRevisionNo() <= 0
                || resolveDTO.getApplicationId() == null
                || resolveDTO.getApplicationId() <= 0
                || resolveDTO.getEntityType() == null
                || resolveDTO.getEntityType().isBlank()
                || resolveDTO.getAsOf() == null) {
            // 判定时刻必须显式给出：报表按受理时刻解释，平台不隐式取"服务器现在"
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static void requireReverseRequest(AiMasterMappingReverseDTO reverseDTO) {
        if (reverseDTO == null
                || reverseDTO.getApplicationId() == null
                || reverseDTO.getApplicationId() <= 0
                || reverseDTO.getEntityType() == null
                || reverseDTO.getEntityType().isBlank()
                || reverseDTO.getSourceKey() == null
                || reverseDTO.getSourceKey().isBlank()
                || reverseDTO.getAsOf() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
