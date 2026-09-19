package com.basicframework.module.ai.service.run;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RUN_NOT_FOUND;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunIdempotencyDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunIdempotencyMapper;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.domain.runtime.AiRunRequestDigest;
import com.basicframework.module.ai.enums.AiFieldRules;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.AiConversationSubject;
import com.basicframework.module.ai.service.conversation.AiConversationSubjectResolver;
import com.basicframework.module.ai.service.conversation.dto.AiConversationRunContextDTO;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptDTO;
import com.basicframework.module.ai.service.run.dto.AiRunAcceptResultDTO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceRunSnapshotDTO;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 持久运行与幂等受理实现（O02）。
 *
 * <p>受理顺序固定：校验输入 → 解析可信主体 → 校验服务归属 → 计算请求摘要 → 命中幂等则复用 →
 * 解析并固定发布版本 → 事务内建立幂等记录、运行与首任务。并发由 (主体, 幂等键) 唯一键兜底：
 * 冲突后**在事务之外**回读赢家记录并比较摘要，相同返回原运行、不同返回 409。
 */
@Service
@RequiredArgsConstructor
public class AiRunServiceImpl implements AiRunService {

    /** 单次受理允许的附件数上限。 */
    private static final int MAX_ATTACHMENTS = 20;

    /** 附件标识长度上限。 */
    private static final int MAX_ATTACHMENT_KEY_LENGTH = 128;

    /** 业务上下文长度上限（与 V59 的会话上下文列一致）。 */
    private static final int MAX_CONTEXT_LENGTH = 4_000;

    private final AiRunMapper runMapper;

    private final AiRunIdempotencyMapper idempotencyMapper;

    private final AiRunAcceptanceWriter acceptanceWriter;

    private final AiConversationSubjectResolver subjectResolver;

    private final AiConversationService conversationService;

    private final AiServiceService serviceService;

    private final AiServiceReleaseService releaseService;

    @Override
    public AiRunAcceptResultDTO accept(AiRunAcceptDTO acceptDTO) {
        validate(acceptDTO);
        AiConversationSubject subject = requireSubject();
        AiServiceDO service = serviceService.getService(acceptDTO.getServiceId());
        if (service == null || !subject.applicationId().equals(service.getAppId())) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        String digest = AiRunRequestDigest.compute(
                acceptDTO.getServiceId(),
                acceptDTO.getConversationId(),
                acceptDTO.getMessage(),
                acceptDTO.getAttachmentKeys(),
                acceptDTO.getBusinessContext());

        // 1) 先读幂等记录：命中且摘要一致直接复用，绝不重新发起模型调用
        AiRunAcceptResultDTO reused = reuseIfPresent(subject, acceptDTO.getIdempotencyKey(), digest);
        if (reused != null) {
            return reused;
        }

        // 2) 解析本次运行的固定版本（会话已固定则沿用固定版本，否则按别名解析并写入会话）
        AiServiceRunSnapshotDTO snapshot = resolveSnapshot(subject, acceptDTO);
        try {
            AiRunDO created =
                    acceptanceWriter.create(subject, acceptDTO, digest, snapshot, AiRunAcceptanceWriter.newRunKey());
            return AiRunAcceptResultDTO.of(created, snapshot.getRelease().getReleaseVersion(), false);
        } catch (DuplicateKeyException conflict) {
            // 3) 并发冲突：内层事务已回滚，事务之外回读赢家记录并比较摘要
            AiRunAcceptResultDTO winner = reuseIfPresent(subject, acceptDTO.getIdempotencyKey(), digest);
            if (winner == null) {
                // 运行键碰撞等其它唯一键冲突：不返回假成功，交由调用方重试
                throw exception(AI_IDEMPOTENCY_CONFLICT);
            }
            return winner;
        }
    }

    @Override
    public AiRunDO getRun(Long id) {
        AiConversationSubject subject = requireSubject();
        AiRunDO run = id == null
                ? null
                : runMapper.selectOwned(
                        id, subject.applicationId(), subject.subjectTypeName(), subject.externalUserId());
        if (run == null) {
            throw exception(AI_RUN_NOT_FOUND);
        }
        return run;
    }

    @Override
    public PageResult<AiRunDO> getPage(PageParam pageParam) {
        return acceptanceWriter.page(pageParam, requireSubject());
    }

    /** 命中幂等时返回首次受理的运行；同键异摘要直接 409。 */
    private AiRunAcceptResultDTO reuseIfPresent(AiConversationSubject subject, String idempotencyKey, String digest) {
        AiRunIdempotencyDO existing = idempotencyMapper.selectByKey(
                subject.applicationId(), subject.subjectTypeName(), subject.externalUserId(), idempotencyKey);
        if (existing == null) {
            return null;
        }
        if (!digest.equals(existing.getRequestDigest())) {
            // 同一幂等键提交了不同请求：拒绝，而不是用旧结果冒充新请求
            throw exception(AI_IDEMPOTENCY_CONFLICT);
        }
        AiRunDO run = runMapper.selectById(existing.getRunId());
        if (run == null) {
            throw exception(AI_RUN_NOT_FOUND);
        }
        return AiRunAcceptResultDTO.of(run, releaseVersionOf(run), true);
    }

    private Integer releaseVersionOf(AiRunDO run) {
        return releaseService.listReleases(run.getServiceId()).stream()
                .filter(release -> release.getId().equals(run.getReleaseId()))
                .map(release -> release.getReleaseVersion())
                .findFirst()
                .orElse(null);
    }

    /**
     * 解析并固定本次运行的版本。
     *
     * <p>会话已固定发布版本时走固定值解析（`loadRunContext` 会按**当前**授权重新判定固定版本）；
     * 否则按别名解析并把解析结果写入会话，使后续消息沿用同一版本（FR-07 的会话版本语义）。
     */
    private AiServiceRunSnapshotDTO resolveSnapshot(AiConversationSubject subject, AiRunAcceptDTO acceptDTO) {
        if (acceptDTO.getConversationId() == null) {
            return releaseService.resolveForNewRun(acceptDTO.getServiceId());
        }
        AiConversationRunContextDTO context = conversationService.loadRunContext(acceptDTO.getConversationId(), 1);
        AiConversationDO conversation = context.getConversation();
        if (conversation.getReleaseId() != null) {
            if (!acceptDTO.getServiceId().equals(conversation.getServiceId())) {
                // 会话固定的版本属于另一个服务：受理请求与服务不一致，拒绝而不是静默换版本
                throw exception(AI_REQUEST_INVALID);
            }
            AiServiceRunSnapshotDTO pinned = releaseService.resolvePinnedRun(context.getPin());
            return pinned;
        }
        if (conversation.getServiceId() != null && !acceptDTO.getServiceId().equals(conversation.getServiceId())) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiServiceRunSnapshotDTO snapshot = releaseService.resolveForNewRun(acceptDTO.getServiceId());
        conversationService.bindRelease(
                conversation.getId(), snapshot.getRelease().getId(), conversation.getVersion());
        return snapshot;
    }

    private AiConversationSubject requireSubject() {
        return subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_RESOURCE_NOT_FOUND));
    }

    private static void validate(AiRunAcceptDTO acceptDTO) {
        if (acceptDTO == null
                || acceptDTO.getServiceId() == null
                || !AiFieldRules.isValidIdempotencyKey(acceptDTO.getIdempotencyKey())
                || !StringUtils.hasText(acceptDTO.getMessage())
                || acceptDTO.getMessage().length() > AiFieldRules.MESSAGE_MAX_LENGTH
                || !StringUtils.hasText(acceptDTO.getDataLevel())) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiOutboundLevel.parse(acceptDTO.getDataLevel()).orElseThrow(() -> exception(AI_REQUEST_INVALID));
        List<String> attachments = acceptDTO.getAttachmentKeys() == null ? List.of() : acceptDTO.getAttachmentKeys();
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw exception(AI_REQUEST_INVALID);
        }
        for (String attachment : attachments) {
            if (!StringUtils.hasText(attachment) || attachment.length() > MAX_ATTACHMENT_KEY_LENGTH) {
                throw exception(AI_REQUEST_INVALID);
            }
        }
        String context = acceptDTO.getBusinessContext();
        if (!StringUtils.hasText(context)) {
            return;
        }
        if (context.length() > MAX_CONTEXT_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(context, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (parsed == null) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
