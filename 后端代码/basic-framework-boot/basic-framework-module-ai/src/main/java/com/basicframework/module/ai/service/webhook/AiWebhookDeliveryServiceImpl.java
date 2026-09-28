package com.basicframework.module.ai.service.webhook;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_DELIVERY_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_DELIVERY_NOT_REDELIVERABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_TARGET_DISABLED;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryAttemptDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookTargetDO;
import com.basicframework.module.ai.dal.mysql.webhook.AiWebhookDeliveryAttemptMapper;
import com.basicframework.module.ai.dal.mysql.webhook.AiWebhookDeliveryMapper;
import com.basicframework.module.ai.service.webhook.dto.AiWebhookDeliveryLeaseDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook 投递实现（X10）：入队即冻结正文与编号 → 租约领取 → 落结论（尝试行 + 状态迁移）。
 *
 * <p>关键语义：
 * <ul>
 *   <li><b>正文与编号入队即冻结</b>：重试复用同一份字节与同一个投递编号，因此接收端的去重与验签
 *       在重试之间仍然成立（只有签名时间戳与尝试序号逐次变化）；</li>
 *   <li><b>退避有上限</b>：{@code base * 2^(attempt-1)}，并有上限封顶；超限的中止是**独立结论**
 *       （重试预算耗尽），不掩盖最后一次的真实原因；</li>
 *   <li><b>尝试行先落、状态后迁</b>：即使投递行因栅栏失效没有更新（租约被接管），这次尝试的事实仍然留痕；
 *       尝试序号由领取时递增的计数决定，因此不会与接管的 worker 冲突。</li>
 * </ul>
 */
@Slf4j
@Service
public class AiWebhookDeliveryServiceImpl implements AiWebhookDeliveryService {

    /** 单次领取上限（有界批量，避免一次领取过多导致长事务与超时）。 */
    private static final int MAX_CLAIM_LIMIT = 50;

    /** 租约上限（秒）。 */
    private static final int MAX_LEASE_SECONDS = 600;

    /** 入队上限（单次补漏的投递行数）。 */
    private static final int MAX_ENQUEUE_LIMIT = 200;

    /** 投递编号前缀（对外可识别，长度受列宽约束）。 */
    private static final String DELIVERY_NO_PREFIX = "whd_";

    /** 补漏扫描的终态运行状态（与 {@link AiWebhookEventTypes} 的事件一一对应）。 */
    private static final List<String> RUN_TERMINAL_STATUSES =
            List.of(AiRunDO.STATUS_SUCCEEDED, AiRunDO.STATUS_FAILED, AiRunDO.STATUS_CANCELLED);

    private final AiWebhookDeliveryMapper deliveryMapper;

    private final AiWebhookDeliveryAttemptMapper attemptMapper;

    private final AiWebhookTargetService targetService;

    private final int retryBaseSeconds;

    private final int retryMaxSeconds;

    /** 最近窗口长度（分钟）：每轮重扫这一段里最新的终态运行，既"新的先发"也兜住提交晚于扫描的行。 */
    private final int scanRecentMinutes;

    /**
     * 写入侧的事务边界：**单批有界**（最多 limit 条投递行 + 一次水位推进）一次提交。
     * 扫描在事务外完成；这里不重放长扫描，也不把整轮（多目标）的活儿塞进一个事务。
     */
    private final TransactionTemplate batchTransaction;

    /** 构造器注入：退避与补漏窗口来自配置，默认 30 秒起步、10 分钟封顶、最近窗口 10 分钟。 */
    public AiWebhookDeliveryServiceImpl(
            AiWebhookDeliveryMapper deliveryMapper,
            AiWebhookDeliveryAttemptMapper attemptMapper,
            AiWebhookTargetService targetService,
            PlatformTransactionManager transactionManager,
            @Value("${basic-framework.ai.webhook.retry-base-seconds:30}") int retryBaseSeconds,
            @Value("${basic-framework.ai.webhook.retry-max-seconds:600}") int retryMaxSeconds,
            @Value("${basic-framework.ai.webhook.enqueue-scan-recent-minutes:10}") int scanRecentMinutes) {
        this.deliveryMapper = deliveryMapper;
        this.attemptMapper = attemptMapper;
        this.targetService = targetService;
        this.batchTransaction = new TransactionTemplate(transactionManager);
        this.retryBaseSeconds = Math.max(1, retryBaseSeconds);
        this.retryMaxSeconds = Math.max(this.retryBaseSeconds, retryMaxSeconds);
        this.scanRecentMinutes = Math.max(1, scanRecentMinutes);
    }

    @Override
    public int enqueueTerminalRuns(int limit) {
        // 扫描窗口的"现在"取自数据本身（该应用最新一次运行更新时间），不用应用时钟：
        // 运行行的 update_time 可能由数据库时钟写入，两侧时钟/时区不一致时按应用时钟切窗口会与数据错位。
        int budget = effectiveEnqueueLimit(limit);
        int created = 0;
        for (AiWebhookTargetDO target : targetService.listEnabled()) {
            if (created >= budget) {
                break;
            }
            created += enqueueForTarget(target, budget - created);
        }
        return created;
    }

    /**
     * 单个目标的一轮有界补漏：两条**各自有界**的路径，合起来既不丢事件、也不让"新的"排在历史后面。
     *
     * <ol>
     *   <li><b>水位补漏</b>：升序扫 {@code [水位, 最近窗口左端)}，整段覆盖后水位单调推进到窗口右端。
     *       这一段是历史：越旧的行越早就已提交，不需要时钟余量；停机再久也只是每轮补一段；</li>
     *   <li><b>最近窗口</b>：每轮重扫 {@code [最近窗口左端, 数据锚点]} 里最新的未投递终态运行。
     *       它既保住"新的先发"，也是"写完但尚未提交"的兜底：这类行的更新时间就在最近，
     *       提交后必然落回最近窗口被重扫到——因此水位推进不会造成静默丢失。</li>
     * </ol>
     *
     * 两条路径都只读各自窗口（窗口长度 + LIMIT 双重封顶）：读取在事务外完成，写入按"单批有界"提交。
     */
    private int enqueueForTarget(AiWebhookTargetDO target, int budget) {
        List<String> statuses = subscribedRunStatuses(target);
        if (statuses.isEmpty()) {
            // 没有订阅任何终态事件：这个目标不产生投递（也不推进水位，重新订阅后仍从原水位补齐）
            return 0;
        }
        LocalDateTime anchor = deliveryMapper.selectLatestRunUpdateTime(target.getApplicationId());
        if (anchor == null) {
            // 该应用还没有任何运行：没有可扫的数据（锚点会在第一次运行出现后自然前移）
            return 0;
        }
        int maxAttempts =
                target.getMaxAttempts() == null ? AiWebhookTargetDO.DEFAULT_MAX_ATTEMPTS : target.getMaxAttempts();
        LocalDateTime floor = target.getEnqueueWatermark() == null
                ? AiWebhookTargetDO.ENQUEUE_WATERMARK_EPOCH
                : target.getEnqueueWatermark();
        LocalDateTime recentFloor = anchor.minusMinutes(scanRecentMinutes);
        int created = 0;
        // 历史一段先走：一半预算是它的下限，保证有积压时水位一定在前进
        if (floor.isBefore(recentFloor)) {
            created += enqueueBacklog(target, statuses, maxAttempts, floor, recentFloor, Math.max(1, budget / 2));
        }
        int remaining = budget - created;
        if (remaining > 0) {
            // 最近窗口每轮重扫：最新的先发，同时兜住"提交晚于扫描"的幻影行
            // （windowEnd 传 null：这条路径**不推进水位**，否则会越过还没补到的历史）
            created += writeBatch(
                    deliveryMapper.selectRecentTerminalRuns(
                            target.getId(),
                            target.getApplicationId(),
                            statuses,
                            recentFloor,
                            anchor,
                            maxAttempts,
                            remaining),
                    target,
                    floor,
                    null,
                    remaining);
        }
        return created;
    }

    /**
     * 水位补漏：升序读取 {@code [floor, windowEnd)} 的未投递终态运行（右端是最近窗口左端，两段不重叠）。
     *
     * <p>水位只在整段处理完之后推进：候选不足 limit 条说明整段已覆盖 → 推进到 windowEnd；
     * 否则推进到最后一条已处理的候选（{@code >=} 边界让同一秒内的候选下一轮仍会被读到）。
     * 插入失败时只推进到最后一条成功的候选，**不跳过失败行**——每一轮都是可恢复的。
     */
    private int enqueueBacklog(
            AiWebhookTargetDO target,
            List<String> statuses,
            int maxAttempts,
            LocalDateTime floor,
            LocalDateTime windowEnd,
            int limit) {
        List<AiWebhookDeliveryDO> candidates = deliveryMapper.selectPendingCandidates(
                target.getId(), target.getApplicationId(), statuses, floor, windowEnd, maxAttempts, limit);
        return writeBatch(candidates, target, floor, windowEnd, limit);
    }

    /**
     * 单批写入（最多 limit 条投递行，必要时加一次水位推进）在一个**短事务**里提交：候选是扫描阶段
     * 定好的，事务里没有扫描、没有网络，只有有界的插入与一次 UPDATE。
     *
     * <p>某条插入失败（连接抖动等）时停在那条之前、水位只推进到最后一条成功的候选：
     * 失败行留给下一轮补漏，不跳过、也不让整批回滚重做。{@code windowEnd} 为 {@code null}
     * 表示这条路径不推进水位（最近窗口重扫只是把最新的先发出去）。
     */
    private int writeBatch(
            List<AiWebhookDeliveryDO> candidates,
            AiWebhookTargetDO target,
            LocalDateTime floor,
            LocalDateTime windowEnd,
            int batchLimit) {
        Integer created = batchTransaction.execute(status -> {
            int inserted = 0;
            LocalDateTime processed = null;
            boolean interrupted = false;
            for (AiWebhookDeliveryDO candidate : candidates) {
                try {
                    if (insertCandidate(candidate)) {
                        inserted++;
                    }
                    processed = candidate.getOccurredTime();
                } catch (RuntimeException exception) {
                    log.warn("Webhook 投递入队失败（原因已脱敏），将在下一轮补漏重试：target={}", target.getId());
                    interrupted = true;
                    break;
                }
            }
            if (windowEnd != null) {
                // 整段覆盖（候选不足 limit 条）才推进到段尾；否则推进到最后一条已处理的候选
                LocalDateTime advancedTo = interrupted || candidates.size() >= batchLimit ? processed : windowEnd;
                if (advancedTo != null && advancedTo.isAfter(floor)) {
                    targetService.advanceEnqueueWatermark(target.getId(), advancedTo);
                }
            }
            return inserted;
        });
        return created == null ? 0 : created;
    }

    /** 补齐一条候选的正文、编号与预算后插入；并发入队由唯一键兜底（返回 false 表示已存在的是事实）。 */
    private boolean insertCandidate(AiWebhookDeliveryDO candidate) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        // 正文与投递编号在此冻结：重试复用同一份字节，接收端的去重与验签在重试之间仍然成立
        String payload = AiWebhookPayload.build(
                candidate.getEventType(),
                candidate.getResourceKey(),
                statusOfEvent(candidate.getEventType()),
                candidate.getOccurredTime());
        AiWebhookDeliveryDO delivery = candidate
                .setDeliveryNo(nextDeliveryNo())
                .setPayloadJson(payload)
                .setPayloadDigest(
                        AiWebhookSignature.sha256Hex(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .setStatus(AiWebhookDeliveryDO.STATUS_PENDING)
                .setAttemptCount(0)
                .setMaxAttempts(
                        candidate.getMaxAttempts() == null
                                ? AiWebhookTargetDO.DEFAULT_MAX_ATTEMPTS
                                : candidate.getMaxAttempts())
                .setNextAttemptTime(now)
                .setClaimedEpoch(0)
                .setVersion(0);
        try {
            deliveryMapper.insert(delivery);
            return true;
        } catch (DuplicateKeyException concurrentWinner) {
            // 并发入队：唯一键兜底，已存在的投递行才是唯一事实（不重建、不覆盖正文与编号）
            log.debug("Webhook 投递已由并发 worker 入队（target={}, event={}）", delivery.getTargetId(), delivery.getEventType());
            return false;
        }
    }

    /** 事件白名单 → 本次扫描的终态运行状态集合（可走索引的 {@code status IN}，不再是逐行 JSON 解析）。 */
    private static List<String> subscribedRunStatuses(AiWebhookTargetDO target) {
        List<String> subscribed = AiWebhookEventTypes.parse(target.getEventTypes());
        List<String> statuses = new ArrayList<>();
        for (String runStatus : RUN_TERMINAL_STATUSES) {
            if (subscribed.contains(AiWebhookEventTypes.ofRunStatus(runStatus))) {
                statuses.add(runStatus);
            }
        }
        return statuses;
    }

    @Override
    public List<AiWebhookDeliveryLeaseDTO> claim(String workerId, int limit, int leaseSeconds) {
        LocalDateTime now = LocalDateTime.now();
        int effectiveLimit = Math.min(Math.max(1, limit), MAX_CLAIM_LIMIT);
        int effectiveLease = Math.min(Math.max(1, leaseSeconds), MAX_LEASE_SECONDS);
        List<AiWebhookDeliveryLeaseDTO> leases = new ArrayList<>();
        for (AiWebhookDeliveryDO candidate : deliveryMapper.selectClaimable(now, effectiveLimit)) {
            // CAS：被别的 worker 抢先（0 行）就放弃这条，不重试抢占
            if (deliveryMapper.claim(candidate.getId(), workerId, now.plusSeconds(effectiveLease), now) == 0) {
                continue;
            }
            AiWebhookDeliveryDO claimed = deliveryMapper.selectById(candidate.getId());
            leases.add(new AiWebhookDeliveryLeaseDTO()
                    .setDeliveryId(claimed.getId())
                    .setOwner(claimed.getLeaseOwner())
                    .setEpoch(claimed.getClaimedEpoch())
                    .setAttempt(claimed.getAttemptCount()));
        }
        return leases;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverExpiredLeases(int retryDelaySeconds, int limit) {
        LocalDateTime now = LocalDateTime.now();
        return deliveryMapper.recoverExpired(
                now,
                now.plusSeconds(Math.max(0, retryDelaySeconds)),
                AiWebhookFailureCodes.DELIVERY_EXHAUSTED,
                Math.min(Math.max(1, limit), MAX_CLAIM_LIMIT));
    }

    @Override
    public AiWebhookDeliveryDO getForExecution(Long deliveryId) {
        return deliveryId == null ? null : deliveryMapper.selectById(deliveryId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean finish(AiWebhookDeliveryLeaseDTO lease, AiWebhookDeliveryOutcome outcome) {
        if (lease == null || outcome == null) {
            throw new IllegalArgumentException("Webhook 投递结论不能为空");
        }
        AiWebhookDeliveryDO delivery = deliveryMapper.selectById(lease.getDeliveryId());
        if (delivery == null) {
            // 投递行已不可见（被清理/删除）：尝试事实无处归属，也不假装更新成功
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        recordAttempt(lease, outcome, now);
        if (outcome.isDelivered()) {
            return deliveryMapper.markDelivered(lease.getDeliveryId(), lease.getOwner(), lease.getEpoch(), now) > 0;
        }
        if (!outcome.isRetryable()) {
            return deliveryMapper.markFailed(
                            lease.getDeliveryId(),
                            lease.getOwner(),
                            lease.getEpoch(),
                            outcome.errorCode(),
                            outcome.errorCode())
                    > 0;
        }
        if (lease.getAttempt() >= delivery.getMaxAttempts()) {
            // 预算耗尽：独立结论 + 保留最后一次的真实原因
            return deliveryMapper.markFailed(
                            lease.getDeliveryId(),
                            lease.getOwner(),
                            lease.getEpoch(),
                            AiWebhookFailureCodes.DELIVERY_EXHAUSTED,
                            outcome.errorCode())
                    > 0;
        }
        return deliveryMapper.markRetry(
                        lease.getDeliveryId(),
                        lease.getOwner(),
                        lease.getEpoch(),
                        now.plusSeconds(backoffSeconds(lease.getAttempt())).truncatedTo(ChronoUnit.SECONDS),
                        outcome.errorCode())
                > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void redeliver(Long id) {
        AiWebhookDeliveryDO delivery = requireDelivery(id);
        if (!AiWebhookDeliveryDO.STATUS_FAILED.equals(delivery.getStatus())) {
            // 只有死信可以人工重投：成功/在途/待投递的投递不接受人工干预（避免与自动流程打架）
            throw exception(AI_WEBHOOK_DELIVERY_NOT_REDELIVERABLE);
        }
        AiWebhookTargetDO target;
        try {
            target = targetService.get(delivery.getTargetId());
        } catch (ServiceException exception) {
            // 目标已被删除：按"停用即停发"处理，不投递到无人认领的地址
            throw exception(AI_WEBHOOK_TARGET_DISABLED);
        }
        if (!AiWebhookTargetDO.STATUS_ENABLED.equals(target.getStatus())) {
            throw exception(AI_WEBHOOK_TARGET_DISABLED);
        }
        // 追加一份与目标配置一致的重试预算：尝试计数与尝试序号保持单调递增（不重置、不撞唯一键）
        int extraAttempts = target.getMaxAttempts() == null
                ? AiWebhookTargetDO.DEFAULT_MAX_ATTEMPTS
                : Math.max(1, target.getMaxAttempts());
        // 秒级截断：next_attempt_time 是 datetime(0)，带亚秒的值会被 MySQL 向上取整成"未来 1 秒"，
        // 刚被重投的投递在下一秒之前不可领取（与媒体任务受理时的写入约定一致）
        if (deliveryMapper.redeliver(id, extraAttempts, LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)) == 0) {
            // 并发重投：另一个操作已经放回队列（或状态已变），按当前事实拒绝本次
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public AiWebhookDeliveryDO get(Long id) {
        return requireDelivery(id);
    }

    @Override
    public PageResult<AiWebhookDeliveryDO> getPage(
            PageParam pageParam, Long targetId, String status, String eventType) {
        return deliveryMapper.selectPage(pageParam, targetId, status, eventType);
    }

    @Override
    public List<AiWebhookDeliveryAttemptDO> getAttempts(Long deliveryId) {
        requireDelivery(deliveryId);
        return attemptMapper.selectByDelivery(deliveryId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int pruneAttempts(Duration retention, int limit) {
        Duration effective = retention == null || retention.isNegative() ? Duration.ofDays(30) : retention;
        return attemptMapper.deleteBefore(
                LocalDateTime.now().minus(effective), Math.min(Math.max(1, limit), MAX_ENQUEUE_LIMIT));
    }

    /** 追加尝试留痕（每次**完成**的尝试一条；投递行的尝试计数在领取时递增）。 */
    private void recordAttempt(AiWebhookDeliveryLeaseDTO lease, AiWebhookDeliveryOutcome outcome, LocalDateTime now) {
        long durationMs = Math.max(0, outcome.durationMs());
        attemptMapper.insert(new AiWebhookDeliveryAttemptDO()
                .setDeliveryId(lease.getDeliveryId())
                .setAttemptNo(lease.getAttempt())
                .setOutcome(outcome.attemptOutcome())
                .setErrorCode(outcome.errorCode())
                .setHttpStatus(outcome.httpStatus())
                .setSignatureTimestamp(outcome.signatureTimestamp())
                .setDurationMs(durationMs)
                // 开始时间由实测耗时反推：不写两个相同的时间冒充"没有耗时"
                .setStartedTime(now.minusNanos(durationMs * 1_000_000L))
                .setFinishedTime(now));
    }

    /** 指数退避（有上限封顶）：第 1 次失败等 base，第 2 次等 2×base……最大不超过 retryMaxSeconds。 */
    private long backoffSeconds(int attempt) {
        int exponent = Math.min(Math.max(0, attempt - 1), 16);
        long seconds = (long) retryBaseSeconds << exponent;
        return Math.min(seconds, retryMaxSeconds);
    }

    private AiWebhookDeliveryDO requireDelivery(Long id) {
        AiWebhookDeliveryDO delivery = id == null ? null : deliveryMapper.selectById(id);
        if (delivery == null) {
            throw exception(AI_WEBHOOK_DELIVERY_NOT_FOUND);
        }
        return delivery;
    }

    /** 事件类型 → 运行状态（入队扫描用 {@code 'RUN.' + status} 推导，这里反向取回同一事实）。 */
    private static String statusOfEvent(String eventType) {
        if (eventType == null || !eventType.startsWith("RUN.")) {
            throw exception(AI_REQUEST_INVALID);
        }
        return eventType.substring("RUN.".length());
    }

    private static int effectiveEnqueueLimit(int limit) {
        return Math.min(Math.max(1, limit), MAX_ENQUEUE_LIMIT);
    }

    private static String nextDeliveryNo() {
        return DELIVERY_NO_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
}
