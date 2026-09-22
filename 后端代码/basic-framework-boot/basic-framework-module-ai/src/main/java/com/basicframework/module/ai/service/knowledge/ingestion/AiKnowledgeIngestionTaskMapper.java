package com.basicframework.module.ai.service.knowledge.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 入库任务 Mapper（K03）。 */
@Mapper
public interface AiKnowledgeIngestionTaskMapper extends BaseMapperX<AiKnowledgeIngestionTaskDO> {

    /** 按版本与类型定位（存活行唯一）。 */
    default AiKnowledgeIngestionTaskDO selectByVersion(Long documentVersionId, String taskKind) {
        return selectOne(new LambdaQueryWrapperX<AiKnowledgeIngestionTaskDO>()
                .eq(AiKnowledgeIngestionTaskDO::getDocumentVersionId, documentVersionId)
                .eq(AiKnowledgeIngestionTaskDO::getTaskKind, taskKind));
    }

    /** 某文档的任务（编号倒序）。 */
    default List<AiKnowledgeIngestionTaskDO> selectByDocument(Long documentId) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeIngestionTaskDO>()
                .eq(AiKnowledgeIngestionTaskDO::getDocumentId, documentId)
                .orderByDesc(AiKnowledgeIngestionTaskDO::getId));
    }

    /** 分页（按状态/知识库过滤）。 */
    default PageResult<AiKnowledgeIngestionTaskDO> selectPage(
            PageParam pageParam, Long knowledgeBaseId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiKnowledgeIngestionTaskDO>()
                        .eqIfPresent(AiKnowledgeIngestionTaskDO::getKnowledgeBaseId, knowledgeBaseId)
                        .eqIfPresent(AiKnowledgeIngestionTaskDO::getStatus, status)
                        .orderByDesc(AiKnowledgeIngestionTaskDO::getId));
    }

    /** 待领取的任务（含到期可重试的），按下次可领取时间与编号升序。 */
    default List<AiKnowledgeIngestionTaskDO> selectClaimable(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<AiKnowledgeIngestionTaskDO>()
                .eq(AiKnowledgeIngestionTaskDO::getStatus, AiKnowledgeIngestionTaskDO.STATUS_QUEUED)
                .le(AiKnowledgeIngestionTaskDO::getNextAttemptTime, now)
                .orderByAsc(AiKnowledgeIngestionTaskDO::getNextAttemptTime)
                .orderByAsc(AiKnowledgeIngestionTaskDO::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * CAS 领取：只有仍处于 QUEUED 的行能被领取，领取时写入 owner/epoch/到期时间并递增尝试次数。
     * 返回 0 表示被别的 worker 抢先，调用方必须放弃这条任务。
     */
    @Update("UPDATE ai_knowledge_ingestion_task SET status = 'RUNNING', lease_owner = #{owner},"
            + " claimed_epoch = claimed_epoch + 1, attempt_count = attempt_count + 1,"
            + " lease_expires_time = #{leaseExpiresTime}, heartbeat_time = #{now},"
            + " next_attempt_time = #{now}, version = version + 1"
            + " WHERE id = #{taskId} AND status = 'QUEUED' AND deleted = b'0'")
    int claim(
            @Param("taskId") Long taskId,
            @Param("owner") String owner,
            @Param("leaseExpiresTime") LocalDateTime leaseExpiresTime,
            @Param("now") LocalDateTime now);

    /** 续租（栅栏：owner + epoch 必须匹配且租约未过期）。 */
    @Update("UPDATE ai_knowledge_ingestion_task SET lease_expires_time = #{leaseExpiresTime},"
            + " heartbeat_time = #{now}, version = version + 1"
            + " WHERE id = #{taskId} AND status = 'RUNNING' AND lease_owner = #{owner}"
            + " AND claimed_epoch = #{epoch} AND lease_expires_time > #{now} AND deleted = b'0'")
    int heartbeat(
            @Param("taskId") Long taskId,
            @Param("owner") String owner,
            @Param("epoch") Integer epoch,
            @Param("leaseExpiresTime") LocalDateTime leaseExpiresTime,
            @Param("now") LocalDateTime now);

    /** 写入终态并释放租约（栅栏同上）。 */
    @Update("UPDATE ai_knowledge_ingestion_task SET status = #{status}, last_error_code = #{errorCode},"
            + " lease_owner = NULL, lease_expires_time = NULL, version = version + 1"
            + " WHERE id = #{taskId} AND status = 'RUNNING' AND lease_owner = #{owner}"
            + " AND claimed_epoch = #{epoch} AND deleted = b'0'")
    int finish(
            @Param("taskId") Long taskId,
            @Param("owner") String owner,
            @Param("epoch") Integer epoch,
            @Param("status") String status,
            @Param("errorCode") String errorCode);

    /** 租约过期的任务数（观测）。 */
    default long countExpired(LocalDateTime now) {
        return selectCount(new LambdaQueryWrapper<AiKnowledgeIngestionTaskDO>()
                .eq(AiKnowledgeIngestionTaskDO::getStatus, AiKnowledgeIngestionTaskDO.STATUS_RUNNING)
                .lt(AiKnowledgeIngestionTaskDO::getLeaseExpiresTime, now));
    }

    /** 到期可领取的任务数（观测）。 */
    default long countQueuedReady(LocalDateTime now) {
        return selectCount(new LambdaQueryWrapper<AiKnowledgeIngestionTaskDO>()
                .eq(AiKnowledgeIngestionTaskDO::getStatus, AiKnowledgeIngestionTaskDO.STATUS_QUEUED)
                .le(AiKnowledgeIngestionTaskDO::getNextAttemptTime, now));
    }

    /** 人工重试：把任务放回待领取队列并重置尝试计数（乐观锁 CAS）。 */
    default int requeue(Long taskId, Integer expectedVersion, LocalDateTime nextAttemptTime) {
        return update(
                new AiKnowledgeIngestionTaskDO()
                        .setStatus(AiKnowledgeIngestionTaskDO.STATUS_QUEUED)
                        .setAttemptCount(0)
                        .setNextAttemptTime(nextAttemptTime)
                        .setLeaseOwner(null)
                        .setLeaseExpiresTime(null)
                        .setVersion(expectedVersion + 1),
                new LambdaUpdateWrapper<AiKnowledgeIngestionTaskDO>()
                        .eq(AiKnowledgeIngestionTaskDO::getId, taskId)
                        .eq(AiKnowledgeIngestionTaskDO::getVersion, expectedVersion)
                        .set(AiKnowledgeIngestionTaskDO::getLeaseOwner, null)
                        .set(AiKnowledgeIngestionTaskDO::getLeaseExpiresTime, null));
    }

    /** 过期租约恢复（未达上限回 QUEUED，达上限置 FAILED）；返回处理条数。 */
    @Update("UPDATE ai_knowledge_ingestion_task SET status = CASE WHEN attempt_count >= max_attempts"
            + " THEN 'FAILED' ELSE 'QUEUED' END, last_error_code = CASE WHEN attempt_count >= max_attempts"
            + " THEN 'lease-expired' ELSE last_error_code END, lease_owner = NULL, lease_expires_time = NULL,"
            + " next_attempt_time = #{nextAttemptTime}, version = version + 1"
            + " WHERE status = 'RUNNING' AND lease_expires_time < #{now} AND deleted = b'0' LIMIT #{limit}")
    int recoverExpired(
            @Param("now") LocalDateTime now,
            @Param("nextAttemptTime") LocalDateTime nextAttemptTime,
            @Param("limit") int limit);

    /** 任务计数（按状态；观测与测试用）。 */
    @Select("SELECT COUNT(*) FROM ai_knowledge_ingestion_task WHERE document_version_id = #{documentVersionId}"
            + " AND status = #{status} AND deleted = b'0'")
    long countByVersionAndStatus(@Param("documentVersionId") Long documentVersionId, @Param("status") String status);
}
