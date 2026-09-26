package com.basicframework.module.ai.service.quota;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.module.ai.dal.dataobject.usage.AiQuotaLeaseDO;
import com.basicframework.module.ai.dal.mysql.usage.AiQuotaLeaseMapper;
import com.basicframework.module.ai.service.quota.dto.AiQuotaAcquireDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 并发配额占位实现（Q02）。
 *
 * <p>为什么不只用"计数"：计数在进程崩溃时无法回收（AT-059 的"不永久占位"）。
 * 这里的占位是**带租约的行**，判定口径与查询口径一致："未释放且未到期"才算占用；
 * 到期占位在下一次申请时被顺带回收（懒清理），不依赖额外的清理任务。
 */
@Service
@RequiredArgsConstructor
public class AiQuotaServiceImpl implements AiQuotaService {

    private static final Duration MAX_LEASE = Duration.ofHours(2);

    private final AiQuotaLeaseMapper quotaLeaseMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean acquire(AiQuotaAcquireDTO request) {
        validate(request);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plus(request.getLease());
        String leaseKey = leaseKeyOf(request);

        AiQuotaLeaseDO existing = quotaLeaseMapper.selectByLeaseKey(leaseKey);
        if (existing != null) {
            if (AiQuotaLeaseDO.STATE_ACTIVE.equals(existing.getState())
                    && existing.getLeaseUntil().isAfter(now)) {
                // 同一调用重复申请：续租而不是再占一格（幂等）
                return quotaLeaseMapper.updateWithVersion(
                                new AiQuotaLeaseDO()
                                        .setId(existing.getId())
                                        .setLeaseUntil(leaseUntil)
                                        .setVersion(existing.getVersion() + 1),
                                existing.getVersion())
                        == 1;
            }
            // 已释放或已到期：同一调用标识重新占用（用于重试），仍需受并发上限约束
        }

        if (quotaLeaseMapper.countActive(request.getApplicationId(), now) >= request.getLimit()) {
            // 并发已满：不排队、不阻塞，由调用方回 429 并说明原因
            return false;
        }

        AiQuotaLeaseDO lease = new AiQuotaLeaseDO()
                .setLeaseKey(leaseKey)
                .setApplicationId(request.getApplicationId())
                .setServiceId(request.getServiceId())
                .setInvocationId(request.getInvocationId())
                .setHolderRef(request.getHolderRef())
                .setState(AiQuotaLeaseDO.STATE_ACTIVE)
                .setLeaseUntil(leaseUntil)
                .setVersion(0);
        try {
            quotaLeaseMapper.insert(lease);
            return true;
        } catch (DuplicateKeyException concurrent) {
            // 并发下唯一键判负：视为"这次没拿到"，由调用方重试（不重复占位）
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean renew(String leaseKey, Duration lease) {
        if (!StringUtils.hasText(leaseKey) || lease == null || lease.isZero() || lease.isNegative()) {
            throw exception(AI_REQUEST_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        AiQuotaLeaseDO existing = quotaLeaseMapper.selectByLeaseKey(leaseKey);
        if (existing == null
                || !AiQuotaLeaseDO.STATE_ACTIVE.equals(existing.getState())
                || !existing.getLeaseUntil().isAfter(now)) {
            // 占位已被释放或回收：续租失败，调用方必须停止工作（避免"以为还占着"）
            return false;
        }
        return quotaLeaseMapper.updateWithVersion(
                        new AiQuotaLeaseDO()
                                .setId(existing.getId())
                                .setLeaseUntil(now.plus(lease))
                                .setVersion(existing.getVersion() + 1),
                        existing.getVersion())
                == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(String leaseKey) {
        if (!StringUtils.hasText(leaseKey)) {
            return;
        }
        AiQuotaLeaseDO existing = quotaLeaseMapper.selectByLeaseKey(leaseKey);
        if (existing == null || AiQuotaLeaseDO.STATE_RELEASED.equals(existing.getState())) {
            return;
        }
        quotaLeaseMapper.updateWithVersion(
                new AiQuotaLeaseDO()
                        .setId(existing.getId())
                        .setState(AiQuotaLeaseDO.STATE_RELEASED)
                        .setVersion(existing.getVersion() + 1),
                existing.getVersion());
    }

    @Override
    public long activeCount(Long applicationId) {
        if (applicationId == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        return quotaLeaseMapper.countActive(applicationId, LocalDateTime.now());
    }

    /** 占位键：应用:服务:调用标识（服务为空用 0），保证同一调用只有一条占位行。 */
    static String leaseKeyOf(AiQuotaAcquireDTO request) {
        return request.getApplicationId() + ":" + (request.getServiceId() == null ? 0 : request.getServiceId()) + ":"
                + request.getInvocationId();
    }

    private static void validate(AiQuotaAcquireDTO request) {
        if (request == null
                || request.getApplicationId() == null
                || !StringUtils.hasText(request.getInvocationId())
                || request.getInvocationId().length() > 64
                || request.getLimit() == null
                || request.getLimit() < 1
                || request.getLease() == null
                || request.getLease().isZero()
                || request.getLease().isNegative()
                || request.getLease().compareTo(MAX_LEASE) > 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
