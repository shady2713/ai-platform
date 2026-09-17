package com.basicframework.module.ai.dal.mysql.token;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.token.AiAccessTicketDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiAccessTicketMapper extends BaseMapperX<AiAccessTicketDO> {

    /** 按 token 摘要查票据（唯一索引）。 */
    default AiAccessTicketDO selectByDigest(String tokenDigest) {
        return selectOne(new LambdaQueryWrapperX<AiAccessTicketDO>().eq(AiAccessTicketDO::getTokenDigest, tokenDigest));
    }

    /** 某主体当前的可用票据（撤销应用/主体时批量失效）。 */
    default List<AiAccessTicketDO> selectActive(Long applicationId, String subjectType, String externalUserId) {
        return selectList(new LambdaQueryWrapperX<AiAccessTicketDO>()
                .eq(AiAccessTicketDO::getApplicationId, applicationId)
                .eq(AiAccessTicketDO::getSubjectType, subjectType)
                .eq(AiAccessTicketDO::getExternalUserId, externalUserId == null ? "" : externalUserId)
                .eq(AiAccessTicketDO::getStatus, AiAccessTicketDO.STATUS_ACTIVE));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiAccessTicketDO update, Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiAccessTicketDO>()
                        .eq(AiAccessTicketDO::getId, update.getId())
                        .eq(AiAccessTicketDO::getVersion, expectedVersion));
    }
}
