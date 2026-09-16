package com.basicframework.module.system.dal.mysql.sms;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SmsChannelMapper extends BaseMapperX<SmsChannelDO> {

    default boolean replaceLegacyApiKey(Long id, String legacyValue, String ciphertext) {
        return update(
                        null,
                        new LambdaUpdateWrapper<SmsChannelDO>()
                                .set(SmsChannelDO::getApiKeyCiphertext, ciphertext)
                                .eq(SmsChannelDO::getId, id)
                                .eq(SmsChannelDO::getApiKeyCiphertext, legacyValue))
                == 1;
    }

    default PageResult<SmsChannelDO> selectPage(
            PageParam pageParam, String signature, String code, Integer status, LocalDateTime[] createTime) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<SmsChannelDO>()
                        .likeIfPresent(SmsChannelDO::getSignature, signature)
                        .eqIfPresent(SmsChannelDO::getCode, code)
                        .eqIfPresent(SmsChannelDO::getStatus, status)
                        .betweenIfPresent(SmsChannelDO::getCreateTime, createTime)
                        .orderByDesc(SmsChannelDO::getId));
    }

    default SmsChannelDO selectByCode(String code) {
        return selectOne(SmsChannelDO::getCode, code);
    }

    @Select("SELECT * FROM system_sms_channel WHERE id = #{id} AND deleted = b'0' FOR SHARE")
    SmsChannelDO selectByIdForShare(Long id);

    @Select("SELECT * FROM system_sms_channel WHERE id = #{id} AND deleted = b'0' FOR UPDATE")
    SmsChannelDO selectByIdForUpdate(Long id);
}
