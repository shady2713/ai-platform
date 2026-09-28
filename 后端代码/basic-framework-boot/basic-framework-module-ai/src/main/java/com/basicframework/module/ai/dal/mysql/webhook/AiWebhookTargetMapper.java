package com.basicframework.module.ai.dal.mysql.webhook;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookTargetDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** Webhook 目标 Mapper（X10）：应用内标识唯一、按应用与状态分页、手工乐观锁 CAS。 */
@Mapper
public interface AiWebhookTargetMapper extends BaseMapperX<AiWebhookTargetDO> {

    /** 按应用 + 标识定位（应用内唯一）。 */
    default AiWebhookTargetDO selectByCode(Long applicationId, String code) {
        return selectOne(new LambdaQueryWrapperX<AiWebhookTargetDO>()
                .eq(AiWebhookTargetDO::getApplicationId, applicationId)
                .eq(AiWebhookTargetDO::getCode, code));
    }

    /** 分页（按应用过滤，标识模糊、状态精确；按编号倒序，翻页稳定）。 */
    default PageResult<AiWebhookTargetDO> selectPage(
            PageParam pageParam, Long applicationId, String code, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiWebhookTargetDO>()
                        .eqIfPresent(AiWebhookTargetDO::getApplicationId, applicationId)
                        .likeIfPresent(AiWebhookTargetDO::getCode, code)
                        .eqIfPresent(AiWebhookTargetDO::getStatus, status)
                        .orderByDesc(AiWebhookTargetDO::getId));
    }

    /** 乐观锁 CAS：仅当数据库中的 version 仍为期望值时才更新（框架未启用乐观锁插件，手工实现）。 */
    default int updateWithVersion(AiWebhookTargetDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiWebhookTargetDO>()
                        .eq(AiWebhookTargetDO::getId, update.getId())
                        .eq(AiWebhookTargetDO::getVersion, expectedVersion));
    }

    /** 启用中的目标（补漏扫描的驱动集合；控制面配置，规模由"每应用订阅数"决定，天然有界）。 */
    default List<AiWebhookTargetDO> selectEnabled() {
        return selectList(new LambdaQueryWrapperX<AiWebhookTargetDO>()
                .eq(AiWebhookTargetDO::getStatus, AiWebhookTargetDO.STATUS_ENABLED)
                .orderByAsc(AiWebhookTargetDO::getId));
    }

    /**
     * 单调推进补漏水位（只前进、不回退）。水位是作业的内部进度而不是控制面配置：
     * 这条 UPDATE 不写 {@code version}，因此不会让管理端的乐观锁版本无端失效。
     */
    @Update("UPDATE ai_webhook_target SET enqueue_watermark = #{watermark}"
            + " WHERE id = #{targetId} AND enqueue_watermark < #{watermark} AND deleted = b'0'")
    int advanceEnqueueWatermark(@Param("targetId") Long targetId, @Param("watermark") LocalDateTime watermark);
}
