package com.basicframework.module.system.dal.mysql.sms;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.module.system.dal.dataobject.sms.SmsLogDO;
import com.basicframework.module.system.enums.sms.SmsReceiveStatusEnum;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 短信日志 Mapper（仅保留基础写入能力）
 */
@Mapper
public interface SmsLogMapper extends BaseMapperX<SmsLogDO> {

    default int updateReceiveResult(SmsLogDO update, Long logId, String channelCode, String apiSerialNo) {
        LambdaUpdateWrapper<SmsLogDO> wrapper = providerCorrelation(logId, channelCode, apiSerialNo);
        if (SmsReceiveStatusEnum.SUCCESS.getStatus() == update.getReceiveStatus()) {
            wrapper.ne(SmsLogDO::getReceiveStatus, SmsReceiveStatusEnum.SUCCESS.getStatus());
        } else if (SmsReceiveStatusEnum.FAILURE.getStatus() == update.getReceiveStatus()) {
            wrapper.eq(SmsLogDO::getReceiveStatus, SmsReceiveStatusEnum.INIT.getStatus());
        } else {
            throw new IllegalArgumentException("短信回执状态不受支持");
        }
        return update(update, wrapper);
    }

    default boolean existsByProviderCorrelation(Long logId, String channelCode, String apiSerialNo) {
        return selectCount(providerCorrelation(logId, channelCode, apiSerialNo)) == 1;
    }

    private static LambdaUpdateWrapper<SmsLogDO> providerCorrelation(
            Long logId, String channelCode, String apiSerialNo) {
        return new LambdaUpdateWrapper<SmsLogDO>()
                .eq(logId != null, SmsLogDO::getId, logId)
                .eq(SmsLogDO::getChannelCode, channelCode)
                .eq(SmsLogDO::getApiSerialNo, apiSerialNo);
    }

    @Delete("DELETE FROM system_sms_log WHERE create_time < #{createTime} ORDER BY id LIMIT #{limit}")
    int deleteByCreateTimeLt(@Param("createTime") LocalDateTime createTime, @Param("limit") int limit);
}
