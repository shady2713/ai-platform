package com.basicframework.module.ai.dal.mysql.media;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 媒体任务 Mapper（X03）：幂等定位、领取/续租/落终态（租约栅栏）。 */
@Mapper
public interface AiMediaTaskMapper extends BaseMapperX<AiMediaTaskDO> {

    /** 幂等定位：同一主体在同一应用下的同一 request_key 只受理一次（存活行唯一）。 */
    default AiMediaTaskDO selectByRequestKey(
            Long applicationId, String subjectType, String externalUserId, String requestKey) {
        return selectOne(new LambdaQueryWrapperX<AiMediaTaskDO>()
                .eq(AiMediaTaskDO::getApplicationId, applicationId)
                .eq(AiMediaTaskDO::getSubjectType, subjectType)
                .eq(AiMediaTaskDO::getExternalUserId, externalUserId)
                .eq(AiMediaTaskDO::getRequestKey, requestKey));
    }

    /** 主任务分页（必须带主体范围：应用 + 主体类型 + 主体标识）。 */
    default PageResult<AiMediaTaskDO> selectPage(
            PageParam pageParam,
            Long applicationId,
            String subjectType,
            String externalUserId,
            String mediaKind,
            String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiMediaTaskDO>()
                        .eq(AiMediaTaskDO::getApplicationId, applicationId)
                        .eq(AiMediaTaskDO::getSubjectType, subjectType)
                        .eq(AiMediaTaskDO::getExternalUserId, externalUserId)
                        .eqIfPresent(AiMediaTaskDO::getMediaKind, mediaKind)
                        .eqIfPresent(AiMediaTaskDO::getStatus, status)
                        .orderByDesc(AiMediaTaskDO::getId));
    }

    /** 待领取的任务（含到期可重试的），按下次可领取时间与编号升序。 */
    default List<AiMediaTaskDO> selectClaimable(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<AiMediaTaskDO>()
                .eq(AiMediaTaskDO::getStatus, AiMediaTaskDO.STATUS_QUEUED)
                .le(AiMediaTaskDO::getNextAttemptTime, now)
                .orderByAsc(AiMediaTaskDO::getNextAttemptTime)
                .orderByAsc(AiMediaTaskDO::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * CAS 领取：只有仍处于 QUEUED 的行能被领取，领取时写 owner/epoch/到期时间并递增尝试次数。
     * 返回 0 表示被别的 worker 抢先，调用方必须放弃这条任务。
     */
    @Update("UPDATE ai_media_task SET status = 'RUNNING', lease_owner = #{owner},"
            + " claimed_epoch = claimed_epoch + 1, attempt_count = attempt_count + 1,"
            + " lease_expires_time = #{leaseExpiresTime}, heartbeat_time = #{now},"
            + " version = version + 1"
            + " WHERE id = #{taskId} AND status = 'QUEUED' AND deleted = b'0'")
    int claim(
            @Param("taskId") Long taskId,
            @Param("owner") String owner,
            @Param("leaseExpiresTime") LocalDateTime leaseExpiresTime,
            @Param("now") LocalDateTime now);

    /** 续租（栅栏：owner + epoch 必须匹配且租约未过期）。 */
    @Update("UPDATE ai_media_task SET lease_expires_time = #{leaseExpiresTime}, heartbeat_time = #{now},"
            + " version = version + 1"
            + " WHERE id = #{taskId} AND status = 'RUNNING' AND lease_owner = #{owner}"
            + " AND claimed_epoch = #{epoch} AND lease_expires_time > #{now} AND deleted = b'0'")
    int heartbeat(
            @Param("taskId") Long taskId,
            @Param("owner") String owner,
            @Param("epoch") Integer epoch,
            @Param("leaseExpiresTime") LocalDateTime leaseExpiresTime,
            @Param("now") LocalDateTime now);

    /** 写终态并释放租约（栅栏同上）：晚到的执行结果命中 0 行，不能覆盖已写入的终态。 */
    @Update("UPDATE ai_media_task SET status = #{status}, failure_code = #{failureCode}, result_count = #{resultCount},"
            + " usage_unit = #{usageUnit}, usage_quantity = #{usageQuantity}, usage_source = #{usageSource},"
            + " lease_owner = NULL, lease_expires_time = NULL, version = version + 1"
            + " WHERE id = #{taskId} AND status = 'RUNNING' AND lease_owner = #{owner}"
            + " AND claimed_epoch = #{epoch} AND deleted = b'0'")
    int finish(
            @Param("taskId") Long taskId,
            @Param("owner") String owner,
            @Param("epoch") Integer epoch,
            @Param("status") String status,
            @Param("failureCode") String failureCode,
            @Param("resultCount") int resultCount,
            @Param("usageUnit") String usageUnit,
            @Param("usageQuantity") Long usageQuantity,
            @Param("usageSource") String usageSource);

    /** 取消（只允许待领取状态；执行中不可取消，见服务层语义）。返回 0 表示状态已变，按当前事实回读。 */
    @Update("UPDATE ai_media_task SET status = 'CANCELLED', version = version + 1"
            + " WHERE id = #{taskId} AND application_id = #{applicationId} AND subject_type = #{subjectType}"
            + " AND external_user_id = #{externalUserId} AND status = 'QUEUED' AND deleted = b'0'")
    int cancel(
            @Param("taskId") Long taskId,
            @Param("applicationId") Long applicationId,
            @Param("subjectType") String subjectType,
            @Param("externalUserId") String externalUserId);

    /** 过期租约恢复（未达上限回 QUEUED，达上限置 FAILED）；返回处理条数。 */
    @Update("UPDATE ai_media_task SET status = CASE WHEN attempt_count >= max_attempts THEN 'FAILED' ELSE 'QUEUED' END,"
            + " failure_code = CASE WHEN attempt_count >= max_attempts THEN 'lease-expired' ELSE failure_code END,"
            + " lease_owner = NULL, lease_expires_time = NULL, next_attempt_time = #{nextAttemptTime},"
            + " version = version + 1"
            + " WHERE status = 'RUNNING' AND lease_expires_time < #{now} AND deleted = b'0' LIMIT #{limit}")
    int recoverExpired(
            @Param("now") LocalDateTime now,
            @Param("nextAttemptTime") LocalDateTime nextAttemptTime,
            @Param("limit") int limit);

    /** 租约过期的任务数（观测）。 */
    default long countExpired(LocalDateTime now) {
        return selectCount(new LambdaQueryWrapper<AiMediaTaskDO>()
                .eq(AiMediaTaskDO::getStatus, AiMediaTaskDO.STATUS_RUNNING)
                .lt(AiMediaTaskDO::getLeaseExpiresTime, now));
    }

    /** 某状态的计数（观测与测试用）。 */
    @Select("SELECT COUNT(*) FROM ai_media_task WHERE status = #{status} AND deleted = b'0'")
    long countByStatus(@Param("status") String status);
}
