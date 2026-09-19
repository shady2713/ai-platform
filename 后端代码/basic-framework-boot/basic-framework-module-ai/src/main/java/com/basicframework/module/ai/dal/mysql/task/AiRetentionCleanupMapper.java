package com.basicframework.module.ai.dal.mysql.task;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 保留期清理 SQL（O06）。
 *
 * <p>每一步都是"带守卫的批量删除"：守卫条件保证**仍被引用**的行不会被删——
 * 非终态运行的事件/任务/运行不删，被非终态运行引用的会话不删。
 * 删除顺序与引用方向一致（事件 → 任务 → 运行 → 消息 → 会话），中途失败也不会留下悬挂引用。
 *
 * <p>写法说明：全部使用**单表 DELETE + 子查询守卫 + LIMIT**。MySQL 的多表 DELETE
 * （含 {@code DELETE alias FROM ... JOIN ...}）不允许 LIMIT，而清理必须能按批次推进，
 * 因此守卫放在子查询里、批次上限放在 LIMIT 上。
 */
@Mapper
public interface AiRetentionCleanupMapper {

    /** 清理终态运行的过期事件（非终态运行的事件一律保留）。 */
    @Delete(
            """
            DELETE FROM ai_run_event
            WHERE create_time < #{before}
              AND run_id IN (SELECT id FROM ai_run WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
            LIMIT #{limit}
            """)
    int deleteEventsOfTerminalRuns(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 清理终态运行的过期任务（RUNNING 的任务一律保留）。 */
    @Delete(
            """
            DELETE FROM ai_run_task
            WHERE update_time < #{before}
              AND status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')
              AND run_id IN (SELECT id FROM ai_run WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
            LIMIT #{limit}
            """)
    int deleteTasksOfTerminalRuns(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /**
     * 清理终态运行的过期幂等记录。
     *
     * <p>幂等记录通过外键引用运行（RESTRICT），因此必须在运行行之前清理：
     * 这也是"清理失败不会删除仍有用引用"的具体体现——依赖方向由外键保证，
     * 清理顺序必须显式跟随它，否则数据库会直接拒绝删除。
     */
    @Delete(
            """
            DELETE FROM ai_run_idempotency
            WHERE update_time < #{before}
              AND run_id IN (SELECT id FROM ai_run WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
            LIMIT #{limit}
            """)
    int deleteIdempotencyOfTerminalRuns(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 清理终态运行的过期运行行（还有未终态任务时保留）。 */
    @Delete(
            """
            DELETE FROM ai_run
            WHERE update_time < #{before}
              AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
              AND NOT EXISTS (
                  SELECT 1 FROM ai_run_task t
                  WHERE t.run_id = ai_run.id AND t.status IN ('QUEUED', 'RUNNING')
              )
              AND NOT EXISTS (SELECT 1 FROM ai_run_idempotency i WHERE i.run_id = ai_run.id)
              AND NOT EXISTS (SELECT 1 FROM ai_run_event e WHERE e.run_id = ai_run.id)
            LIMIT #{limit}
            """)
    int deleteTerminalRuns(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 清理已关闭会话的过期消息（会话仍被非终态运行引用时保留）。 */
    @Delete(
            """
            DELETE FROM ai_conversation_message
            WHERE update_time < #{before}
              AND conversation_id IN (
                  SELECT id FROM ai_conversation c
                  WHERE c.status = 'DELETED'
                    AND NOT EXISTS (SELECT 1 FROM ai_run r WHERE r.conversation_id = c.id)
              )
            LIMIT #{limit}
            """)
    int deleteMessagesOfClosedConversations(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 清理已关闭的过期会话（会话仍被非终态运行引用时保留）。 */
    @Delete(
            """
            DELETE FROM ai_conversation
            WHERE update_time < #{before}
              AND status = 'DELETED'
              AND NOT EXISTS (SELECT 1 FROM ai_run r WHERE r.conversation_id = ai_conversation.id)
              AND NOT EXISTS (
                  SELECT 1 FROM ai_conversation_message m WHERE m.conversation_id = ai_conversation.id
              )
            LIMIT #{limit}
            """)
    int deleteClosedConversations(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
