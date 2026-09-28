package com.basicframework.module.ai.service.media;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.adapter.file.AiFileSubjectResolver;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.mysql.media.AiMediaAssetMapper;
import com.basicframework.module.ai.dal.mysql.media.AiMediaTaskMapper;
import com.basicframework.module.ai.service.file.dto.AiFileSubject;
import com.basicframework.module.ai.service.media.dto.AiMediaAssetDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaStepOutcome;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskLeaseDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskSubmitDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 媒体任务实现（X03）：受理即落库（固定端点与配置版本）→ 后台按租约领取执行 → 租约栅栏写终态。
 *
 * <p>关键语义：
 * <ul>
 *   <li><b>幂等</b>：同主体 + 同 request_key 只受理一次；同键不同参数按冲突拒绝，不做"改参数复用旧结果"；</li>
 *   <li><b>取消只对未开始的任务生效</b>：QUEUED 可取消；RUNNING 阶段上游调用已发出，无法中断，
 *       按状态冲突拒绝（不谎报"已取消"）；已终态的任务重复取消按幂等处理，返回当前事实；</li>
 *   <li><b>失败只落稳定码</b>：任务行只写平台错误码（如 {@code 1_003_010_004}）或脱敏原因码，
 *       不落上游报文与地址；</li>
 *   <li><b>用量如实</b>：上游没给用量就记 UNKNOWN 且数值为空，不写 0。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiMediaTaskServiceImpl implements AiMediaTaskService {

    /** 幂等键长度上限（与列宽一致，避免截断）。 */
    private static final int MAX_REQUEST_KEY_LENGTH = 40;

    /** 文本输入长度上限（提示词/指令/合成文本，与列宽一致）。 */
    private static final int MAX_INPUT_TEXT_LENGTH = 2000;

    /** 单次领取上限（有界批量，避免一次领取过多导致长事务与超时）。 */
    private static final int MAX_CLAIM_LIMIT = 50;

    /** 租约上限（秒）。 */
    private static final int MAX_LEASE_SECONDS = 3600;

    /** 默认最大尝试次数（与列默认值一致）。 */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final AiMediaTaskMapper taskMapper;

    private final AiMediaAssetMapper assetMapper;

    private final AiFileSubjectResolver subjectResolver;

    private final AiModelEndpointService endpointService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMediaTaskResultDTO submit(AiMediaTaskSubmitDTO request) {
        requireSubmittable(request);
        AiFileSubject subject = currentSubject();
        AiMediaTaskDO existing = taskMapper.selectByRequestKey(
                subject.applicationId(),
                subject.subjectType().name(),
                subject.externalUserId(),
                request.getRequestKey());
        if (existing != null) {
            return reuse(existing, request);
        }
        AiModelEndpointDO endpoint = endpointService.getEnabledEndpoint(request.getEndpointId());
        AiMediaTaskDO task = new AiMediaTaskDO()
                .setRequestKey(request.getRequestKey())
                .setApplicationId(subject.applicationId())
                .setSubjectType(subject.subjectType().name())
                .setExternalUserId(subject.externalUserId())
                .setMediaKind(request.getMediaKind())
                .setOperation(request.getOperation())
                .setCapability(request.getCapability())
                .setEndpointId(endpoint.getId())
                .setEndpointConfigRevision(endpoint.getConfigRevision())
                .setModelRef(currentModelRef(endpoint.getId()))
                .setInputText(request.getInputText())
                .setSourceFileId(request.getSourceFileId())
                .setSourceMime(request.getSourceMime())
                .setSourceSizeBytes(request.getSourceSizeBytes())
                .setSourceSha256(request.getSourceSha256())
                .setTargetSize(request.getTargetSize())
                .setOutputCount(request.getOutputCount() == null ? 1 : request.getOutputCount())
                .setOutputFormat(request.getOutputFormat())
                .setVoice(request.getVoice())
                .setLanguageHint(request.getLanguageHint())
                .setStatus(AiMediaTaskDO.STATUS_QUEUED)
                .setResultCount(0)
                .setAttemptCount(0)
                .setMaxAttempts(DEFAULT_MAX_ATTEMPTS)
                // 秒级截断：next_attempt_time 是 datetime(0)，带亚秒的值会被 MySQL 向上取整成"未来 1 秒"，
                // 刚受理的任务在下一秒之前不可领取（与 K03 入库任务的写入约定一致）
                .setNextAttemptTime(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))
                .setClaimedEpoch(0)
                .setUsageSource(AiMediaTaskDO.USAGE_SOURCE_UNKNOWN)
                .setVersion(0);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException concurrentWinner) {
            // 并发同键：唯一键兜底，落库的赢家才是唯一受理事实；按它核对参数一致性
            AiMediaTaskDO winner = taskMapper.selectByRequestKey(
                    subject.applicationId(),
                    subject.subjectType().name(),
                    subject.externalUserId(),
                    request.getRequestKey());
            if (winner == null) {
                throw concurrentWinner;
            }
            return reuse(winner, request);
        }
        return AiMediaTaskResultDTO.from(task).withAssets(List.of());
    }

    @Override
    public AiMediaTaskResultDTO getTask(Long taskId) {
        AiMediaTaskDO task = requireOwnedTask(taskId);
        return AiMediaTaskResultDTO.from(task).withAssets(assetsOf(task.getId()));
    }

    @Override
    public PageResult<AiMediaTaskResultDTO> getTaskPage(PageParam pageParam, String mediaKind, String status) {
        AiFileSubject subject = currentSubject();
        PageResult<AiMediaTaskDO> page = taskMapper.selectPage(
                pageParam,
                subject.applicationId(),
                subject.subjectType().name(),
                subject.externalUserId(),
                mediaKind,
                status);
        List<AiMediaTaskResultDTO> list = page.getList().stream()
                .map(task -> AiMediaTaskResultDTO.from(task).withAssets(assetsOf(task.getId())))
                .toList();
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMediaTaskResultDTO cancel(Long taskId) {
        AiMediaTaskDO task = requireOwnedTask(taskId);
        if (taskMapper.cancel(task.getId(), task.getApplicationId(), task.getSubjectType(), task.getExternalUserId())
                == 0) {
            // 状态已经变了：按当前事实回答，不谎报取消
            AiMediaTaskDO current = requireOwnedTask(taskId);
            if (AiMediaTaskDO.STATUS_RUNNING.equals(current.getStatus())) {
                throw exception(AI_STATE_CONFLICT);
            }
            return AiMediaTaskResultDTO.from(current).withAssets(assetsOf(current.getId()));
        }
        return getTask(taskId);
    }

    @Override
    public List<AiMediaAssetDTO> getAssets(Long taskId) {
        requireOwnedTask(taskId);
        return assetsOf(taskId);
    }

    @Override
    public List<AiMediaTaskLeaseDTO> claim(String workerId, int limit, int leaseSeconds) {
        if (!StringUtils.hasText(workerId)) {
            throw new IllegalArgumentException("worker 标识不能为空");
        }
        int effectiveLimit = Math.min(Math.max(1, limit), MAX_CLAIM_LIMIT);
        int effectiveLease = Math.min(Math.max(1, leaseSeconds), MAX_LEASE_SECONDS);
        LocalDateTime now = LocalDateTime.now();
        List<AiMediaTaskLeaseDTO> leases = new ArrayList<>();
        for (AiMediaTaskDO candidate : taskMapper.selectClaimable(now, effectiveLimit)) {
            // CAS：被别的 worker 抢先（0 行）就放弃这条，不重试抢占
            if (taskMapper.claim(candidate.getId(), workerId, now.plusSeconds(effectiveLease), now) == 0) {
                continue;
            }
            AiMediaTaskDO claimed = taskMapper.selectById(candidate.getId());
            leases.add(new AiMediaTaskLeaseDTO()
                    .setTaskId(claimed.getId())
                    .setOwner(claimed.getLeaseOwner())
                    .setEpoch(claimed.getClaimedEpoch())
                    .setAttempt(claimed.getAttemptCount()));
        }
        return leases;
    }

    @Override
    public AiMediaTaskDO getTaskForExecution(Long taskId) {
        return taskId == null ? null : taskMapper.selectById(taskId);
    }

    @Override
    public boolean heartbeat(AiMediaTaskLeaseDTO lease, int leaseSeconds) {
        if (lease == null) {
            return false;
        }
        int effectiveLease = Math.min(Math.max(1, leaseSeconds), MAX_LEASE_SECONDS);
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.heartbeat(
                        lease.getTaskId(), lease.getOwner(), lease.getEpoch(), now.plusSeconds(effectiveLease), now)
                > 0;
    }

    @Override
    public boolean finish(AiMediaTaskLeaseDTO lease, AiMediaStepOutcome outcome) {
        if (lease == null || outcome == null || !isTerminal(outcome.getStatus())) {
            throw new IllegalArgumentException("媒体任务终态只能是 SUCCEEDED 或 FAILED");
        }
        return taskMapper.finish(
                        lease.getTaskId(),
                        lease.getOwner(),
                        lease.getEpoch(),
                        outcome.getStatus(),
                        outcome.getFailureCode(),
                        Math.max(0, outcome.getResultCount()),
                        outcome.getUsageUnit(),
                        outcome.getUsageQuantity(),
                        outcome.getUsageSource() == null
                                ? AiMediaTaskDO.USAGE_SOURCE_UNKNOWN
                                : outcome.getUsageSource())
                > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverExpiredLeases(int retryDelaySeconds, int limit) {
        LocalDateTime now = LocalDateTime.now();
        return taskMapper.recoverExpired(
                now, now.plusSeconds(Math.max(0, retryDelaySeconds)), Math.min(Math.max(1, limit), MAX_CLAIM_LIMIT));
    }

    /** 幂等复用：参数一致返回既有任务；参数不同按冲突拒绝（不允许"换参数复用旧结果"）。 */
    private AiMediaTaskResultDTO reuse(AiMediaTaskDO existing, AiMediaTaskSubmitDTO request) {
        if (!sameRequest(existing, request)) {
            throw exception(AI_IDEMPOTENCY_CONFLICT);
        }
        return AiMediaTaskResultDTO.from(existing).withAssets(assetsOf(existing.getId()));
    }

    /**
     * 受理参数一致性核对：以受理时写入的列为准（端点/模型是受理时固定的值，不参与比较）。
     * 源文件按**编号 + 摘要**比较：同一编号但摘要不同说明文件内容已变，不能当作同一次请求。
     */
    private static boolean sameRequest(AiMediaTaskDO existing, AiMediaTaskSubmitDTO request) {
        return Objects.equals(existing.getMediaKind(), request.getMediaKind())
                && Objects.equals(existing.getOperation(), request.getOperation())
                && Objects.equals(existing.getCapability(), request.getCapability())
                && Objects.equals(existing.getEndpointId(), request.getEndpointId())
                && Objects.equals(existing.getInputText(), request.getInputText())
                && Objects.equals(existing.getSourceFileId(), request.getSourceFileId())
                && Objects.equals(existing.getSourceSha256(), request.getSourceSha256())
                && Objects.equals(existing.getTargetSize(), request.getTargetSize())
                && Objects.equals(existing.getOutputFormat(), request.getOutputFormat())
                && Objects.equals(existing.getVoice(), request.getVoice())
                && Objects.equals(existing.getLanguageHint(), request.getLanguageHint())
                && Objects.equals(
                        existing.getOutputCount(), request.getOutputCount() == null ? 1 : request.getOutputCount());
    }

    private void requireSubmittable(AiMediaTaskSubmitDTO request) {
        if (request == null || request.getEndpointId() == null || request.getEndpointId() <= 0) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (!StringUtils.hasText(request.getRequestKey())
                || request.getRequestKey().length() > MAX_REQUEST_KEY_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (!StringUtils.hasText(request.getMediaKind())
                || !StringUtils.hasText(request.getOperation())
                || !StringUtils.hasText(request.getCapability())) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (request.getInputText() != null && request.getInputText().length() > MAX_INPUT_TEXT_LENGTH) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (request.getSourceFileId() != null && request.getSourceFileId() <= 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private AiMediaTaskDO requireOwnedTask(Long taskId) {
        AiFileSubject subject = currentSubject();
        AiMediaTaskDO task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null
                || !Objects.equals(task.getApplicationId(), subject.applicationId())
                || !Objects.equals(task.getSubjectType(), subject.subjectType().name())
                || !Objects.equals(task.getExternalUserId(), subject.externalUserId())) {
            // 越权与不存在同语义：不区分"别人的任务"和"没有这个任务"
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return task;
    }

    private List<AiMediaAssetDTO> assetsOf(Long taskId) {
        return assetMapper.selectByTask(taskId).stream()
                .map(AiMediaAssetDTO::from)
                .toList();
    }

    private AiFileSubject currentSubject() {
        return subjectResolver.resolveCurrent().orElseThrow(() -> exception(AI_RESOURCE_NOT_FOUND));
    }

    /** 受理时固定的模型标识：取当前配置版本（与能力总览同源）。 */
    private String currentModelRef(Long endpointId) {
        return endpointService.getRevisions(endpointId).stream()
                .findFirst()
                .map(AiModelEndpointRevisionDO::getModelId)
                .orElseThrow(() -> exception(AI_STATE_CONFLICT));
    }

    private static boolean isTerminal(String status) {
        return AiMediaTaskDO.STATUS_SUCCEEDED.equals(status) || AiMediaTaskDO.STATUS_FAILED.equals(status);
    }
}
