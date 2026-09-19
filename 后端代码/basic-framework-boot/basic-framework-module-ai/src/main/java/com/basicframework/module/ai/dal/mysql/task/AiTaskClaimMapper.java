package com.basicframework.module.ai.dal.mysql.task;

import com.basicframework.module.ai.dal.dataobject.run.AiRunTaskDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 任务领取与租约 SQL（O03）。
 *
 * <p>与 O02 的运行聚合映射（{@code dal.mysql.run}）分开：这里只放"领取/续租/落库/恢复"四类
 * 带栅栏的原子语句，全部是单条 UPDATE 的条件更新（CAS），因此不依赖长事务或应用层锁。
 *
 * <p>栅栏语义：
 * <ul>
 *   <li>领取：{@code status='QUEUED' AND claimed_epoch = 期望代次} → 置 RUNNING 且代次 +1；</li>
 *   <li>续租/落库：{@code lease_owner = 本次 owner AND claimed_epoch = 本次代次 AND status='RUNNING'}，
 *       租约过期被他人接管后代次已递增，迟到的旧 worker 命中 0 行；</li>
 *   <li>恢复：只处理 {@code lease_expires_time < NOW()} 的行，未达上限回 QUEUED，达到上限置 FAILED。</li>
 * </ul>
 */
@Mapper
public interface AiTaskClaimMapper {

    /** 可领取的候选任务（按编号升序，限定批次大小；领取仍由 CAS 决定成败）。 */
    @Select(
            """
            SELECT * FROM ai_run_task
            WHERE deleted = 0 AND status = 'QUEUED'
              AND (next_attempt_time IS NULL OR next_attempt_time <= NOW())
            ORDER BY id
            LIMIT #{limit}
            """)
    List<AiRunTaskDO> selectClaimable(@Param("limit") int limit);

    /** CAS 领取：只有期望代次与库中一致且仍待领取时才成功。 */
    @Update(
            """
            UPDATE ai_run_task
            SET status = 'RUNNING',
                lease_owner = #{owner},
                lease_expires_time = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND),
                heartbeat_time = NOW(),
                claimed_epoch = claimed_epoch + 1,
                attempt_count = attempt_count + 1,
                version = version + 1
            WHERE id = #{id} AND deleted = 0 AND status = 'QUEUED' AND claimed_epoch = #{expectedEpoch}
            """)
    int claim(
            @Param("id") Long id,
            @Param("expectedEpoch") int expectedEpoch,
            @Param("owner") String owner,
            @Param("leaseSeconds") int leaseSeconds);

    /** 续租：owner 与代次都命中才生效（旧 worker 迟到无法续租）。 */
    @Update(
            """
            UPDATE ai_run_task
            SET lease_expires_time = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND),
                heartbeat_time = NOW(),
                version = version + 1
            WHERE id = #{id} AND deleted = 0 AND status = 'RUNNING'
              AND lease_owner = #{owner} AND claimed_epoch = #{epoch}
              AND lease_expires_time > NOW()
            """)
    int heartbeat(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("epoch") int epoch,
            @Param("leaseSeconds") int leaseSeconds);

    /** 落库终态：owner 与代次都命中才生效，并释放租约。 */
    @Update(
            """
            UPDATE ai_run_task
            SET status = #{status},
                lease_owner = NULL,
                lease_expires_time = NULL,
                heartbeat_time = NOW(),
                last_error_code = #{errorCode},
                version = version + 1
            WHERE id = #{id} AND deleted = 0 AND status = 'RUNNING'
              AND lease_owner = #{owner} AND claimed_epoch = #{epoch}
            """)
    int finish(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("epoch") int epoch,
            @Param("status") String status,
            @Param("errorCode") String errorCode);

    /**
     * 恢复过期租约：未达重试上限的回到 QUEUED 并进入重试等待，达到上限的置 FAILED。
     *
     * @param retryDelaySeconds 重试等待秒数（避免恢复后立刻被同一批 worker 抢占）
     * @param limit             单批上限（避免一次扫全表）
     */
    @Update(
            """
            UPDATE ai_run_task
            SET status = IF(attempt_count >= max_attempts, 'FAILED', 'QUEUED'),
                lease_owner = NULL,
                lease_expires_time = NULL,
                next_attempt_time = DATE_ADD(NOW(), INTERVAL #{retryDelaySeconds} SECOND),
                version = version + 1
            WHERE deleted = 0 AND status = 'RUNNING' AND lease_expires_time < NOW()
            ORDER BY id
            LIMIT #{limit}
            """)
    int recoverExpired(@Param("retryDelaySeconds") int retryDelaySeconds, @Param("limit") int limit);

    /** 仍持有有效租约的任务数（观测与测试用）。 */
    @Select(
            """
            SELECT COUNT(*) FROM ai_run_task
            WHERE deleted = 0 AND status = 'RUNNING' AND lease_expires_time > NOW()
            """)
    int countActiveLeases();
}
