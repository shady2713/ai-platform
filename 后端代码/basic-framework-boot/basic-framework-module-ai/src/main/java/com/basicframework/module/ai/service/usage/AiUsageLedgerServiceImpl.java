package com.basicframework.module.ai.service.usage;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.usage.AiUsageLedgerDO;
import com.basicframework.module.ai.dal.mysql.usage.AiUsageLedgerMapper;
import com.basicframework.module.ai.service.usage.dto.AiUsageRecordDTO;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用量账本服务（Q02）。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>一次真实调用一条</b>：`invocation_id` 唯一 + 冲突吞掉（并发重复写入只累计一次）；
 *       重试若**真的再次请求模型**，调用方必须使用新的 invocationId（服务层不做"合并重试"）；</li>
 *   <li><b>来源如实标注</b>：只接受 REPORTED/ESTIMATED/UNKNOWN；UNKNOWN 时 token 必须留空
 *       （写 0 会被读成"真实用量为 0"，AT-060 明确禁止）；</li>
 *   <li><b>不含秘密与正文</b>：只接受模型标识与端点**引用**；端点地址/密钥不在 DTO 里。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiUsageLedgerServiceImpl implements AiUsageLedgerService {

    private static final Set<String> SOURCES =
            Set.of(AiUsageLedgerDO.SOURCE_REPORTED, AiUsageLedgerDO.SOURCE_ESTIMATED, AiUsageLedgerDO.SOURCE_UNKNOWN);

    private static final Set<String> STATUSES =
            Set.of(AiUsageLedgerDO.STATUS_SUCCEEDED, AiUsageLedgerDO.STATUS_FAILED, AiUsageLedgerDO.STATUS_CANCELLED);

    private final AiUsageLedgerMapper usageLedgerMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean record(AiUsageRecordDTO record) {
        validate(record);
        AiUsageLedgerDO row = new AiUsageLedgerDO()
                .setInvocationId(record.getInvocationId())
                .setRunId(record.getRunId())
                .setTaskId(record.getTaskId())
                .setApplicationId(record.getApplicationId())
                .setServiceId(record.getServiceId())
                .setSubjectRef(record.getSubjectRef())
                .setModelRef(record.getModelRef())
                .setModelRevision(record.getModelRevision())
                .setEndpointRef(record.getEndpointRef())
                .setInputTokens(record.getInputTokens())
                .setOutputTokens(record.getOutputTokens())
                .setUsageSource(record.getUsageSource())
                .setDurationMs(record.getDurationMs())
                .setStatus(record.getStatus())
                .setOccurredAt(LocalDateTime.now());
        try {
            usageLedgerMapper.insert(row);
            return true;
        } catch (DuplicateKeyException duplicated) {
            // 同一调用被重复上报：不重复累计，也不报错（幂等语义）
            return false;
        }
    }

    @Override
    public List<AiUsageLedgerDO> listByRun(Long runId) {
        return runId == null ? List.of() : usageLedgerMapper.selectByRun(runId);
    }

    @Override
    public PageResult<AiUsageLedgerDO> page(
            PageParam pageParam, Long applicationId, Long serviceId, LocalDateTime from, LocalDateTime to) {
        return usageLedgerMapper.selectPage(pageParam, applicationId, serviceId, from, to);
    }

    @Override
    public Map<String, Object> summaryBySource(Long applicationId, LocalDateTime from, LocalDateTime to) {
        requireWindow(applicationId, from, to);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sources", usageLedgerMapper.aggregateBySource(applicationId, from, to));
        // 明确暴露"来源未知"的条数：调用方据此提示"用量为估算/未知"，而不是把它当精确值
        long unknown = usageLedgerMapper.aggregateBySource(applicationId, from, to).stream()
                .filter(row -> AiUsageLedgerDO.SOURCE_UNKNOWN.equals(String.valueOf(row.get("usageSource"))))
                .mapToLong(row -> ((Number) row.getOrDefault("invocationCount", 0)).longValue())
                .sum();
        summary.put("unknownInvocations", unknown);
        return summary;
    }

    @Override
    public List<Map<String, Object>> summaryByService(Long applicationId, LocalDateTime from, LocalDateTime to) {
        requireWindow(applicationId, from, to);
        return usageLedgerMapper.aggregateByService(applicationId, from, to);
    }

    private static void requireWindow(Long applicationId, LocalDateTime from, LocalDateTime to) {
        if (applicationId == null || from == null || to == null || !from.isBefore(to)) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static void validate(AiUsageRecordDTO record) {
        if (record == null
                || !StringUtils.hasText(record.getInvocationId())
                || record.getInvocationId().length() > 64
                || record.getApplicationId() == null
                || !StringUtils.hasText(record.getModelRef())) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (!SOURCES.contains(record.getUsageSource()) || !STATUSES.contains(record.getStatus())) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (AiUsageLedgerDO.SOURCE_UNKNOWN.equals(record.getUsageSource())
                && (record.getInputTokens() != null || record.getOutputTokens() != null)) {
            // 未知来源不允许携带 token：否则读侧会把占位数当成真实用量
            throw exception(AI_REQUEST_INVALID);
        }
        if (record.getRunId() == null && record.getTaskId() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        // 端点只接受引用（编号/别名），地址与密钥形态直接拒绝
        if (record.getEndpointRef() != null
                && (record.getEndpointRef().contains("://")
                        || record.getEndpointRef().length() > 64)) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
