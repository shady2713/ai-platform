package com.basicframework.module.ai.service.knowledge.ingestion;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_INGESTION_TASK_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_INGESTION_TASK_STATE_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.file.AiFileBindingDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.mysql.file.AiFileBindingMapper;
import com.basicframework.module.ai.dal.mysql.knowledge.AiKnowledgeBaseMapper;
import com.basicframework.module.ai.service.file.AiFileBusinessType;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionRequestDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionResultDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionTaskLeaseDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 文档入库实现（K03）。
 *
 * <p>事务边界：{@link #ingest} 的"版本 + 任务"写在同一个事务里；**文件上传不在其中**
 * （文件先由 A07 落库，属于独立资源）。因此失败时的补偿是"释放文件引用"而不是"删文件"：
 * 释放会按 A07 的语义判定是否还有其他引用，避免误删共享文件。
 *
 * <p>归属校验用文件绑定行判定：业务类型必须是 {@code ai_knowledge_document}，业务键必须是该知识库标识——
 * 这样"上传到 A 库的文件不能挂到 B 库"，也顺带挡住"拿会话附件当知识原文"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiKnowledgeIngestionServiceImpl implements AiKnowledgeIngestionService {

    /** 租约上限（秒）：避免 worker 崩溃后长时间占用任务。 */
    private static final int MAX_LEASE_SECONDS = 900;

    private final AiKnowledgeIngestionTaskMapper taskMapper;

    private final AiKnowledgeIngestionWriter writer;

    private final AiKnowledgeBaseMapper baseMapper;

    private final AiFileBindingMapper fileBindingMapper;

    private final AiFileService fileService;

    private final AiKnowledgeDocumentService documentService;

    @Override
    public AiKnowledgeIngestionResultDTO ingest(AiKnowledgeIngestionRequestDTO request) {
        if (request == null || request.getKnowledgeBaseId() == null || request.getFileId() == null) {
            throw exception(AI_KNOWLEDGE_FILE_INVALID);
        }
        try {
            // 归属与知识库校验也在补偿范围内：任何失败都不能留下已上传但没人引用的文件
            AiKnowledgeBaseDO knowledgeBase = requireBase(request.getKnowledgeBaseId());
            requireOwnedKnowledgeFile(request.getFileId(), knowledgeBase.getCode());
            return writer.createVersionAndTask(request, knowledgeBase);
        } catch (RuntimeException failure) {
            // 补偿：版本/任务没建成，已上传的文件必须解除引用（无悬空文件）
            compensateFile(request.getFileId(), failure);
            throw failure;
        }
    }

    @Override
    public List<AiKnowledgeIngestionTaskLeaseDTO> claim(String workerId, int limit, int leaseSeconds) {
        if (!StringUtils.hasText(workerId)) {
            throw exception(AI_KNOWLEDGE_INGESTION_TASK_STATE_INVALID);
        }
        int effectiveLease = Math.min(Math.max(1, leaseSeconds), MAX_LEASE_SECONDS);
        LocalDateTime now = nowSeconds();
        List<AiKnowledgeIngestionTaskLeaseDTO> leases = new ArrayList<>();
        for (AiKnowledgeIngestionTaskDO candidate : taskMapper.selectClaimable(now, Math.max(1, limit))) {
            LocalDateTime expiresAt = now.plusSeconds(effectiveLease);
            if (taskMapper.claim(candidate.getId(), workerId, expiresAt, now) == 0) {
                // 被别的 worker 抢先：跳过而不是重试，避免两个 worker 处理同一版本
                continue;
            }
            AiKnowledgeIngestionTaskDO claimed = taskMapper.selectById(candidate.getId());
            leases.add(new AiKnowledgeIngestionTaskLeaseDTO(
                    claimed.getId(),
                    claimed.getKnowledgeBaseId(),
                    claimed.getDocumentId(),
                    claimed.getDocumentVersionId(),
                    claimed.getTaskKind(),
                    workerId,
                    claimed.getClaimedEpoch(),
                    expiresAt));
        }
        return leases;
    }

    @Override
    public boolean heartbeat(AiKnowledgeIngestionTaskLeaseDTO lease, int leaseSeconds) {
        if (lease == null) {
            return false;
        }
        int effectiveLease = Math.min(Math.max(1, leaseSeconds), MAX_LEASE_SECONDS);
        LocalDateTime now = nowSeconds();
        return taskMapper.heartbeat(lease.taskId(), lease.owner(), lease.epoch(), now.plusSeconds(effectiveLease), now)
                > 0;
    }

    @Override
    public boolean finish(AiKnowledgeIngestionTaskLeaseDTO lease, String status, String errorCode) {
        if (lease == null || !isTerminal(status)) {
            throw exception(AI_KNOWLEDGE_INGESTION_TASK_STATE_INVALID);
        }
        String reason = StringUtils.hasText(errorCode) ? truncate(errorCode) : null;
        return taskMapper.finish(lease.taskId(), lease.owner(), lease.epoch(), status, reason) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverExpiredLeases(int retryDelaySeconds, int limit) {
        LocalDateTime now = nowSeconds();
        // 延迟允许为 0（立即可再领取）：0 表示"立刻重试"，负值按 0 处理
        return taskMapper.recoverExpired(
                now, now.plus(Duration.ofSeconds(Math.max(0, retryDelaySeconds))), Math.max(1, limit));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retry(Long taskId, Integer version) {
        if (taskId == null || version == null) {
            throw exception(AI_KNOWLEDGE_INGESTION_TASK_STATE_INVALID);
        }
        AiKnowledgeIngestionTaskDO task = requireTask(taskId);
        if (!AiKnowledgeIngestionTaskDO.STATUS_FAILED.equals(task.getStatus())
                && !AiKnowledgeIngestionTaskDO.STATUS_UNKNOWN.equals(task.getStatus())) {
            // 仍在执行/已成功/排队中的任务不接受人工重试
            throw exception(AI_KNOWLEDGE_INGESTION_TASK_STATE_INVALID);
        }
        if (taskMapper.requeue(taskId, version, nowSeconds()) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public AiKnowledgeIngestionTaskDO getTask(Long taskId) {
        return requireTask(taskId);
    }

    @Override
    public PageResult<AiKnowledgeIngestionTaskDO> getTaskPage(
            PageParam pageParam, Long knowledgeBaseId, String status) {
        return taskMapper.selectPage(pageParam, knowledgeBaseId, status);
    }

    /** 文件必须属于该知识库：业务类型与业务键都要匹配（purpose + 归属）。 */
    private void requireOwnedKnowledgeFile(Long fileId, String knowledgeBaseCode) {
        AiFileBindingDO binding = fileBindingMapper.selectActiveByFile(fileId).stream()
                .findFirst()
                .orElseThrow(() -> exception(AI_KNOWLEDGE_FILE_INVALID));
        boolean sameType = AiFileBusinessType.KNOWLEDGE_DOCUMENT.code().equalsIgnoreCase(binding.getBusinessType());
        if (!sameType || !knowledgeBaseCode.equals(binding.getBusinessKey())) {
            throw exception(AI_KNOWLEDGE_FILE_INVALID);
        }
    }

    /** 补偿：解除文件引用（best-effort；失败只记录，不掩盖原始异常）。 */
    private void compensateFile(Long fileId, RuntimeException failure) {
        try {
            fileService.release(fileId);
        } catch (RuntimeException compensationFailure) {
            log.warn(
                    "入库失败后的文件引用补偿未成功：fileId={}, reason={}",
                    fileId,
                    compensationFailure.getClass().getSimpleName());
        }
        log.warn(
                "入库失败并已尝试补偿文件引用：fileId={}, reason={}",
                fileId,
                failure.getClass().getSimpleName());
    }

    private AiKnowledgeBaseDO requireBase(Long knowledgeBaseId) {
        AiKnowledgeBaseDO knowledgeBase = baseMapper.selectById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw exception(AI_KNOWLEDGE_FILE_INVALID);
        }
        return knowledgeBase;
    }

    private AiKnowledgeIngestionTaskDO requireTask(Long taskId) {
        AiKnowledgeIngestionTaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            throw exception(AI_KNOWLEDGE_INGESTION_TASK_NOT_FOUND);
        }
        return task;
    }

    /** 统一按秒取整：datetime 列为秒级精度，取整保证"写进去的时间不会比现在晚"。 */
    private static LocalDateTime nowSeconds() {
        return LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    private static boolean isTerminal(String status) {
        return AiKnowledgeIngestionTaskDO.STATUS_SUCCEEDED.equals(status)
                || AiKnowledgeIngestionTaskDO.STATUS_FAILED.equals(status)
                || AiKnowledgeIngestionTaskDO.STATUS_UNKNOWN.equals(status);
    }

    private static String truncate(String value) {
        String single = value.replace('\n', ' ').replace('\r', ' ').trim();
        return single.length() <= 64 ? single : single.substring(0, 64);
    }
}
