package com.basicframework.module.ai.service.authorization;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_ACCESS_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SUBJECT_FEDERATION_APPROVER_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SUBJECT_FEDERATION_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SUBJECT_FEDERATION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SUBJECT_FEDERATION_STATE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SUBJECT_FEDERATION_SUBJECT_UNAVAILABLE;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.federation.AiSubjectFederationDO;
import com.basicframework.module.ai.dal.mysql.federation.AiSubjectFederationMapper;
import com.basicframework.module.ai.dal.mysql.federation.AiSubjectFederationQuery;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.authorization.dto.AiSubjectFederationSubmitDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 跨系统主体联邦映射实现（Y01）。
 *
 * <p>安全语义（每条都有负向测试）：
 * <ol>
 *   <li>提交需要操作员身份（无登录主体直接 403 语义），批准人必须不同于提交人；</li>
 *   <li>两个应用都必须存在且启用，两个主体都必须已登记且 ACTIVE——批准时**再核验一次**，
 *       期间被停用的映射不会获批；</li>
 *   <li>自映射（同一身份指向自己）与缺失身份字段直接 400 语义拒绝；</li>
 *   <li>只有 APPROVED 行参与发现；撤销立即生效、幂等，且重提交会清空上一次审批痕迹。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiSubjectFederationServiceImpl implements AiSubjectFederationService {

    /** 审批说明长度上限（与迁移列宽 256 对齐，留出多字节余量）。 */
    static final int MAX_APPROVAL_NOTE_LENGTH = 200;

    /** 外部用户标识长度上限（与 A02 的 ai_subject 列宽一致）。 */
    static final int MAX_EXTERNAL_USER_ID_LENGTH = 128;

    private final AiSubjectFederationMapper federationMapper;

    private final AiApplicationService applicationService;

    private final AiSubjectService subjectService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submit(AiSubjectFederationSubmitDTO submitDTO) {
        Identity source = identity(
                submitDTO == null ? null : submitDTO.getSourceApplicationId(),
                submitDTO == null ? null : submitDTO.getSourceSubjectType(),
                submitDTO == null ? null : submitDTO.getSourceExternalUserId());
        Identity target = identity(
                submitDTO == null ? null : submitDTO.getTargetApplicationId(),
                submitDTO == null ? null : submitDTO.getTargetSubjectType(),
                submitDTO == null ? null : submitDTO.getTargetExternalUserId());
        if (source.applicationId().equals(target.applicationId())
                && source.subjectType() == target.subjectType()
                && source.externalUserId().equals(target.externalUserId())) {
            // 自己映射到自己没有意义，且会让"跨系统"语义失真
            throw exception(AI_REQUEST_INVALID);
        }
        Long operator = requireOperator();
        requireEnabledApplication(source.applicationId());
        requireEnabledApplication(target.applicationId());
        requireActiveSubject(source);
        requireActiveSubject(target);

        AiSubjectFederationDO existing = federationMapper.selectByIdentity(query(source, target));
        if (existing != null && !AiSubjectFederationDO.STATUS_REVOKED.equals(existing.getStatus())) {
            throw exception(AI_SUBJECT_FEDERATION_DUPLICATE);
        }
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            AiSubjectFederationDO created = new AiSubjectFederationDO()
                    .setSourceApplicationId(source.applicationId())
                    .setSourceSubjectType(source.subjectType().name())
                    .setSourceExternalUserId(source.externalUserId())
                    .setTargetApplicationId(target.applicationId())
                    .setTargetSubjectType(target.subjectType().name())
                    .setTargetExternalUserId(target.externalUserId())
                    .setStatus(AiSubjectFederationDO.STATUS_PENDING)
                    .setRequestedBy(operator)
                    .setRequestedTime(now)
                    .setRevision(1L)
                    .setVersion(0);
            federationMapper.insert(created);
            return created.getId();
        }
        // 已撤销：复用同一行重新提交（清空上一次审批痕迹），映射版本继续递增
        AiSubjectFederationDO resubmit = new AiSubjectFederationDO()
                .setId(existing.getId())
                .setStatus(AiSubjectFederationDO.STATUS_PENDING)
                .setRequestedBy(operator)
                .setRequestedTime(now)
                .setRevision(existing.getRevision() + 1)
                .setVersion(existing.getVersion() + 1);
        if (federationMapper.resubmitAfterRevoke(resubmit, existing.getVersion()) == 0) {
            throw exception(AI_SUBJECT_FEDERATION_STATE_CONFLICT);
        }
        return existing.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, Integer version, String approvalNote) {
        requireVersion(version);
        validateApprovalNote(approvalNote);
        Long operator = requireOperator();
        AiSubjectFederationDO existing = getFederation(id);
        if (!AiSubjectFederationDO.STATUS_PENDING.equals(existing.getStatus())) {
            throw exception(AI_SUBJECT_FEDERATION_STATE_CONFLICT);
        }
        if (operator.equals(existing.getRequestedBy())) {
            // 独立审批：提交人不得批准自己的申请
            throw exception(AI_SUBJECT_FEDERATION_APPROVER_CONFLICT);
        }
        Identity source = identity(
                existing.getSourceApplicationId(), existing.getSourceSubjectType(), existing.getSourceExternalUserId());
        Identity target = identity(
                existing.getTargetApplicationId(), existing.getTargetSubjectType(), existing.getTargetExternalUserId());
        // 批准时刻再核验一次事实：申请期间被停用的主体不会获批
        requireEnabledApplication(source.applicationId());
        requireEnabledApplication(target.applicationId());
        requireActiveSubject(source);
        requireActiveSubject(target);
        AiSubjectFederationDO update = new AiSubjectFederationDO()
                .setId(existing.getId())
                .setStatus(AiSubjectFederationDO.STATUS_APPROVED)
                .setApprovedBy(operator)
                .setApprovedTime(LocalDateTime.now())
                .setApprovalNote(approvalNote)
                .setRevision(existing.getRevision() + 1)
                .setVersion(version + 1);
        if (federationMapper.updateWithVersion(update, version) == 0) {
            throw exception(AI_SUBJECT_FEDERATION_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long id, Integer version) {
        requireVersion(version);
        AiSubjectFederationDO existing = getFederation(id);
        if (AiSubjectFederationDO.STATUS_REVOKED.equals(existing.getStatus())) {
            // 幂等：目标（不再参与发现）已达成，版本号不再参与
            return;
        }
        AiSubjectFederationDO update = new AiSubjectFederationDO()
                .setId(existing.getId())
                .setStatus(AiSubjectFederationDO.STATUS_REVOKED)
                .setRevision(existing.getRevision() + 1)
                .setVersion(version + 1);
        if (federationMapper.updateWithVersion(update, version) == 0) {
            throw exception(AI_SUBJECT_FEDERATION_STATE_CONFLICT);
        }
    }

    @Override
    public AiSubjectFederationDO getFederation(Long id) {
        AiSubjectFederationDO federation = id == null ? null : federationMapper.selectById(id);
        if (federation == null) {
            throw exception(AI_SUBJECT_FEDERATION_NOT_EXISTS);
        }
        return federation;
    }

    @Override
    public PageResult<AiSubjectFederationDO> getFederationPage(
            PageParam pageParam, Long sourceApplicationId, String status) {
        if (pageParam == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (StringUtils.hasText(status)
                && !AiSubjectFederationDO.STATUS_PENDING.equals(status)
                && !AiSubjectFederationDO.STATUS_APPROVED.equals(status)
                && !AiSubjectFederationDO.STATUS_REVOKED.equals(status)) {
            throw exception(AI_REQUEST_INVALID);
        }
        return federationMapper.selectPage(pageParam, sourceApplicationId, status);
    }

    @Override
    public List<AiSubjectFederationDO> listApprovedTargets(
            Long sourceApplicationId, AiSubjectType sourceSubjectType, String sourceExternalUserId) {
        if (sourceApplicationId == null || sourceSubjectType == null) {
            return List.of();
        }
        String normalized = sourceSubjectType == AiSubjectType.APP
                ? ""
                : (sourceExternalUserId == null ? "" : sourceExternalUserId);
        if (sourceSubjectType == AiSubjectType.USER && normalized.isEmpty()) {
            return List.of();
        }
        return federationMapper.selectApprovedBySource(sourceApplicationId, sourceSubjectType.name(), normalized);
    }

    private void requireEnabledApplication(Long applicationId) {
        AiApplicationDO application = applicationService.getApplication(applicationId);
        if (!Boolean.TRUE.equals(application.getEnabled())) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private void requireActiveSubject(Identity identity) {
        if (subjectService
                .findActiveSubject(identity.applicationId(), identity.subjectType(), identity.externalUserId())
                .isEmpty()) {
            throw exception(AI_SUBJECT_FEDERATION_SUBJECT_UNAVAILABLE);
        }
    }

    private static Long requireOperator() {
        Long operator = SecurityFrameworkUtils.getLoginUserId();
        if (operator == null) {
            // 没有操作员身份的调用不能登记或批准跨系统身份映射
            throw exception(AI_ACCESS_DENIED);
        }
        return operator;
    }

    private static void requireVersion(Integer version) {
        if (version == null || version < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static void validateApprovalNote(String approvalNote) {
        if (approvalNote != null && approvalNote.length() > MAX_APPROVAL_NOTE_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static AiSubjectFederationQuery query(Identity source, Identity target) {
        return new AiSubjectFederationQuery()
                .setSourceApplicationId(source.applicationId())
                .setSourceSubjectType(source.subjectType().name())
                .setSourceExternalUserId(source.externalUserId())
                .setTargetApplicationId(target.applicationId())
                .setTargetSubjectType(target.subjectType().name())
                .setTargetExternalUserId(target.externalUserId());
    }

    /** 归一化后的身份事实：APP 主体的外部标识固定为空串。 */
    private static Identity identity(Long applicationId, String subjectType, String externalUserId) {
        AiSubjectType type = AiSubjectType.parse(subjectType).orElseThrow(() -> exception(AI_REQUEST_INVALID));
        if (applicationId == null || applicationId <= 0) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (externalUserId != null && externalUserId.length() > MAX_EXTERNAL_USER_ID_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (type == AiSubjectType.USER && !StringUtils.hasText(externalUserId)) {
            throw exception(AI_REQUEST_INVALID);
        }
        return new Identity(applicationId, type, type == AiSubjectType.APP ? "" : externalUserId.trim());
    }

    private record Identity(Long applicationId, AiSubjectType subjectType, String externalUserId) {}
}
