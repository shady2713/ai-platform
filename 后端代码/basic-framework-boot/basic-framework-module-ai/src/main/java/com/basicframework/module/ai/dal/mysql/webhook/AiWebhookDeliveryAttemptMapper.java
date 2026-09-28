package com.basicframework.module.ai.dal.mysql.webhook;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryAttemptDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Webhook 投递尝试留痕 Mapper（X10）：只追加、按投递读取、按保留期分批清理。 */
@Mapper
public interface AiWebhookDeliveryAttemptMapper extends BaseMapperX<AiWebhookDeliveryAttemptDO> {

    /** 某次投递的全部尝试（按尝试序号升序；这是"每一次尝试的结论"的唯一读取入口）。 */
    default List<AiWebhookDeliveryAttemptDO> selectByDelivery(Long deliveryId) {
        return selectList(new LambdaQueryWrapperX<AiWebhookDeliveryAttemptDO>()
                .eq(AiWebhookDeliveryAttemptDO::getDeliveryId, deliveryId)
                .orderByAsc(AiWebhookDeliveryAttemptDO::getAttemptNo));
    }

    /** 按保留期分批清理（append-retention）；返回清理条数。 */
    @Delete("DELETE FROM ai_webhook_delivery_attempt WHERE create_time < #{before} LIMIT #{limit}")
    int deleteBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
