package com.basicframework.module.system.dal.mysql.sms;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SmsTemplateMapper extends BaseMapperX<SmsTemplateDO> {

    default SmsTemplateDO selectByCode(String code) {
        return selectOne(SmsTemplateDO::getCode, code);
    }

    default PageResult<SmsTemplateDO> selectPage(PageParam pageParam, SmsTemplateQuery query) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<SmsTemplateDO>()
                        .eqIfPresent(SmsTemplateDO::getType, query.getType())
                        .eqIfPresent(SmsTemplateDO::getStatus, query.getStatus())
                        .likeIfPresent(SmsTemplateDO::getCode, query.getCode())
                        .likeIfPresent(SmsTemplateDO::getContent, query.getContent())
                        .likeIfPresent(SmsTemplateDO::getApiTemplateId, query.getApiTemplateId())
                        .eqIfPresent(SmsTemplateDO::getChannelId, query.getChannelId())
                        .betweenIfPresent(SmsTemplateDO::getCreateTime, query.getCreateTime())
                        .orderByDesc(SmsTemplateDO::getId));
    }

    default Long selectCountByChannelId(Long channelId) {
        return selectCount(SmsTemplateDO::getChannelId, channelId);
    }

    @Select("SELECT * FROM system_sms_template WHERE id = #{id} AND deleted = b'0' FOR UPDATE")
    SmsTemplateDO selectByIdForUpdate(Long id);

    @Select(
            """
            SELECT COUNT(*)
            FROM system_sms_template template
            LEFT JOIN system_sms_channel channel
              ON channel.id = template.channel_id AND channel.deleted = b'0'
            WHERE template.deleted = b'0'
              AND channel.id IS NULL
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    int selectOrphanChannelCount();
}
