package com.basicframework.module.ai.dal.mysql.webhook;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Webhook 投递 Mapper（X10）：入队扫描、领取/退避/落终态（租约栅栏）、人工重投与保留期清理。 */
@Mapper
public interface AiWebhookDeliveryMapper extends BaseMapperX<AiWebhookDeliveryDO> {

    /**
     * 有界补漏扫描（水位路径）：读取**单个目标**在 {@code [floor, ceiling)} 时间窗口内、尚未投递的终态运行候选。
     *
     * <p>为什么是"水位 + 时间窗口 + LIMIT"三件套（而不是原来的整表 {@code NOT EXISTS} 反连接）：
     * <ul>
     *   <li>谓词只用可走索引的列（{@code application_id + update_time} 区间 + {@code status IN (...)}），
     *       事件白名单在 Java 侧推导成状态集合，SQL 里没有逐行 {@code JSON_CONTAINS}；</li>
     *   <li>窗口左端是目标行上的补漏水位、右端是最近窗口的左端，作业只用单调推进水位就能重新覆盖一切：
     *       停机再久也只是每轮推进一段，不会永久漏投；</li>
     *   <li>升序 + {@code LIMIT} 让单轮读取量由"到第 limit 条候选为止"封顶，
     *       不再有"每 10 秒把应用的终态运行全表扫一遍再排序截断"的代价。</li>
     * </ul>
     *
     * <p>候选是**半成品投递行**（目标、应用、事件类型、资源引用已填），其余字段由服务层补齐后插入；
     * {@code NOT EXISTS} 仍然保留：水位边界（{@code >=}）与失败重试会重复读到同一行，
     * 已有投递行时不再尝试插入，语义上仍然"每个目标 × 事件 × 资源只有一条投递"。
     */
    @Select("<script>"
            + "SELECT #{targetId} AS target_id, r.application_id AS application_id,"
            + " CONCAT('RUN.', r.status) AS event_type, 'RUN' AS resource_type,"
            + " r.id AS resource_id, r.run_key AS resource_key, r.update_time AS occurred_time,"
            + " #{maxAttempts} AS max_attempts"
            + " FROM ai_run r"
            + " WHERE r.application_id = #{applicationId} AND r.deleted = b'0'"
            + "   AND r.status IN <foreach item='status' collection='statuses' open='(' separator=','"
            + " close=')'>#{status}</foreach>"
            + "   AND r.update_time &gt;= #{floor} AND r.update_time &lt; #{ceiling}"
            + "   AND NOT EXISTS (SELECT 1 FROM ai_webhook_delivery d"
            + "       WHERE d.target_id = #{targetId} AND d.event_type = CONCAT('RUN.', r.status)"
            + "         AND d.resource_type = 'RUN' AND d.resource_id = r.id AND d.deleted = b'0')"
            + " ORDER BY r.update_time ASC"
            + " LIMIT #{limit}"
            + "</script>")
    List<AiWebhookDeliveryDO> selectPendingCandidates(
            @Param("targetId") Long targetId,
            @Param("applicationId") Long applicationId,
            @Param("statuses") List<String> statuses,
            @Param("floor") LocalDateTime floor,
            @Param("ceiling") LocalDateTime ceiling,
            @Param("maxAttempts") Integer maxAttempts,
            @Param("limit") int limit);

    /**
     * 最近窗口重扫（"新的先发"路径）：同一窗口的候选按更新时间**倒序**返回最新的一批。
     *
     * <p>每轮都执行、不推进水位：它保证积压期间新产生的终态运行第一时间入队，也是"写完但尚未提交"
     * （提交晚于扫描）的行最终被读到的兜底——水位路径覆盖到同一行时由 {@code NOT EXISTS} 去重。
     * 右端取 {@code <=}：与 {@code datetime(0)} 同秒的刚写入行不会被漏掉。
     */
    @Select("<script>"
            + "SELECT #{targetId} AS target_id, r.application_id AS application_id,"
            + " CONCAT('RUN.', r.status) AS event_type, 'RUN' AS resource_type,"
            + " r.id AS resource_id, r.run_key AS resource_key, r.update_time AS occurred_time,"
            + " #{maxAttempts} AS max_attempts"
            + " FROM ai_run r"
            + " WHERE r.application_id = #{applicationId} AND r.deleted = b'0'"
            + "   AND r.status IN <foreach item='status' collection='statuses' open='(' separator=','"
            + " close=')'>#{status}</foreach>"
            + "   AND r.update_time &gt;= #{floor} AND r.update_time &lt;= #{ceiling}"
            + "   AND NOT EXISTS (SELECT 1 FROM ai_webhook_delivery d"
            + "       WHERE d.target_id = #{targetId} AND d.event_type = CONCAT('RUN.', r.status)"
            + "         AND d.resource_type = 'RUN' AND d.resource_id = r.id AND d.deleted = b'0')"
            + " ORDER BY r.update_time DESC"
            + " LIMIT #{limit}"
            + "</script>")
    List<AiWebhookDeliveryDO> selectRecentTerminalRuns(
            @Param("targetId") Long targetId,
            @Param("applicationId") Long applicationId,
            @Param("statuses") List<String> statuses,
            @Param("floor") LocalDateTime floor,
            @Param("ceiling") LocalDateTime ceiling,
            @Param("maxAttempts") Integer maxAttempts,
            @Param("limit") int limit);

    /**
     * 该应用最新一次运行更新时间：补漏扫描的**时间锚点**（"数据里的现在"）。
     *
     * <p>为什么不用应用时钟 {@code LocalDateTime.now()}：运行行的 {@code update_time} 可能是数据库用
     * {@code CURRENT_TIMESTAMP} 写的，应用与数据库的时钟/时区不一致时（本仓库的集成环境就是
     * 数据库 UTC、应用 Asia/Shanghai 相差 8 小时），按应用时钟切窗口会与数据完全错位。
     * 取"已有数据里的最大值"作锚点则与数据同源：无论两侧时钟差多少，窗口都落在真实数据上；
     * 新写入的行会在下一轮把锚点推着走，最近窗口每轮重扫，因此不会漏。走
     * {@code (application_id, update_time)} 索引的反向索引扫描，O(1)。
     */
    @Select("SELECT MAX(r.update_time) FROM ai_run r WHERE r.application_id = #{applicationId}"
            + " AND r.deleted = b'0'")
    LocalDateTime selectLatestRunUpdateTime(@Param("applicationId") Long applicationId);

    /** 待领取的投递（含退避到期的重试），按下次可领取时间与编号升序。 */
    default List<AiWebhookDeliveryDO> selectClaimable(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<AiWebhookDeliveryDO>()
                .eq(AiWebhookDeliveryDO::getStatus, AiWebhookDeliveryDO.STATUS_PENDING)
                .le(AiWebhookDeliveryDO::getNextAttemptTime, now)
                .orderByAsc(AiWebhookDeliveryDO::getNextAttemptTime)
                .orderByAsc(AiWebhookDeliveryDO::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * CAS 领取：只有仍处于 PENDING 的行能被领取，领取时写 owner/epoch/到期时间并递增尝试次数。
     * 返回 0 表示被别的 worker 抢先，调用方必须放弃这条投递。
     */
    @Update("UPDATE ai_webhook_delivery SET status = 'RUNNING', lease_owner = #{owner},"
            + " claimed_epoch = claimed_epoch + 1, attempt_count = attempt_count + 1,"
            + " first_attempt_time = COALESCE(first_attempt_time, #{now}),"
            + " lease_expires_time = #{leaseExpiresTime}, heartbeat_time = #{now}, version = version + 1"
            + " WHERE id = #{deliveryId} AND status = 'PENDING' AND deleted = b'0'")
    int claim(
            @Param("deliveryId") Long deliveryId,
            @Param("owner") String owner,
            @Param("leaseExpiresTime") LocalDateTime leaseExpiresTime,
            @Param("now") LocalDateTime now);

    /** 落"已送达"（栅栏：owner + epoch）：清理租约与失败痕迹。 */
    @Update("UPDATE ai_webhook_delivery SET status = 'SUCCEEDED', failure_code = NULL, last_error_code = NULL,"
            + " delivered_time = #{now}, lease_owner = NULL, lease_expires_time = NULL, version = version + 1"
            + " WHERE id = #{deliveryId} AND status = 'RUNNING' AND lease_owner = #{owner}"
            + " AND claimed_epoch = #{epoch} AND deleted = b'0'")
    int markDelivered(
            @Param("deliveryId") Long deliveryId,
            @Param("owner") String owner,
            @Param("epoch") Integer epoch,
            @Param("now") LocalDateTime now);

    /** 落"退避后重试"（栅栏同上）：状态回到 PENDING，写下次可领取时间与最近原因码。 */
    @Update("UPDATE ai_webhook_delivery SET status = 'PENDING', next_attempt_time = #{nextAttemptTime},"
            + " last_error_code = #{lastErrorCode}, lease_owner = NULL, lease_expires_time = NULL,"
            + " version = version + 1"
            + " WHERE id = #{deliveryId} AND status = 'RUNNING' AND lease_owner = #{owner}"
            + " AND claimed_epoch = #{epoch} AND deleted = b'0'")
    int markRetry(
            @Param("deliveryId") Long deliveryId,
            @Param("owner") String owner,
            @Param("epoch") Integer epoch,
            @Param("nextAttemptTime") LocalDateTime nextAttemptTime,
            @Param("lastErrorCode") String lastErrorCode);

    /** 落"失败终态"（栅栏同上）：死信保留失败码与最近原因码，可人工重投。 */
    @Update("UPDATE ai_webhook_delivery SET status = 'FAILED', failure_code = #{failureCode},"
            + " last_error_code = #{lastErrorCode}, lease_owner = NULL, lease_expires_time = NULL,"
            + " version = version + 1"
            + " WHERE id = #{deliveryId} AND status = 'RUNNING' AND lease_owner = #{owner}"
            + " AND claimed_epoch = #{epoch} AND deleted = b'0'")
    int markFailed(
            @Param("deliveryId") Long deliveryId,
            @Param("owner") String owner,
            @Param("epoch") Integer epoch,
            @Param("failureCode") String failureCode,
            @Param("lastErrorCode") String lastErrorCode);

    /**
     * 过期租约恢复：未达上限回 PENDING（按退避继续），达到上限置 FAILED 并记"重试预算耗尽"。
     * 崩溃留下的 RUNNING 行因此有界收敛，不需要人工干预。
     */
    @Update("UPDATE ai_webhook_delivery SET"
            + " status = CASE WHEN attempt_count >= max_attempts THEN 'FAILED' ELSE 'PENDING' END,"
            + " failure_code = CASE WHEN attempt_count >= max_attempts THEN #{exhaustedCode} ELSE failure_code END,"
            + " lease_owner = NULL, lease_expires_time = NULL, next_attempt_time = #{nextAttemptTime},"
            + " version = version + 1"
            + " WHERE status = 'RUNNING' AND lease_expires_time < #{now} AND deleted = b'0' LIMIT #{limit}")
    int recoverExpired(
            @Param("now") LocalDateTime now,
            @Param("nextAttemptTime") LocalDateTime nextAttemptTime,
            @Param("exhaustedCode") String exhaustedCode,
            @Param("limit") int limit);

    /**
     * 人工重投（只对死信）：CAS FAILED → PENDING，并在**保留尝试计数**的前提下追加一份重试预算。
     *
     * <p>为什么不重置计数：尝试序号（`ai_webhook_delivery_attempt.attempt_no`）由领取时的计数决定，
     * 重置计数会让新尝试与历史尝试撞上同一序号（唯一键拒绝），也会让"第几次尝试"这条事实失真。
     * 因此人工重投的语义是"再给一份预算"（`max_attempts = attempt_count + extraAttempts`），
     * 序号继续单调递增。返回 0 表示当前状态不是 FAILED（并发重投或已被重投过），按当前事实回读。
     */
    @Update("UPDATE ai_webhook_delivery SET status = 'PENDING',"
            + " max_attempts = attempt_count + #{extraAttempts}, failure_code = NULL,"
            + " next_attempt_time = #{now}, lease_owner = NULL, lease_expires_time = NULL, version = version + 1"
            + " WHERE id = #{deliveryId} AND status = 'FAILED' AND deleted = b'0'")
    int redeliver(
            @Param("deliveryId") Long deliveryId,
            @Param("extraAttempts") int extraAttempts,
            @Param("now") LocalDateTime now);

    /** 投递分页（死信查看：可按目标、状态、事件类型过滤；按编号倒序）。 */
    default PageResult<AiWebhookDeliveryDO> selectPage(
            PageParam pageParam, Long targetId, String status, String eventType) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiWebhookDeliveryDO>()
                        .eqIfPresent(AiWebhookDeliveryDO::getTargetId, targetId)
                        .eqIfPresent(AiWebhookDeliveryDO::getStatus, status)
                        .eqIfPresent(AiWebhookDeliveryDO::getEventType, eventType)
                        .orderByDesc(AiWebhookDeliveryDO::getId));
    }
}
