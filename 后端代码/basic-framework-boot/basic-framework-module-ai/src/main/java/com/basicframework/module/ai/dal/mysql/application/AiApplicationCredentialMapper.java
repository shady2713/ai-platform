package com.basicframework.module.ai.dal.mysql.application;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationCredentialDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiApplicationCredentialMapper extends BaseMapperX<AiApplicationCredentialDO> {

    /** 某应用的可用凭据（正常最多一条：轮换与吊销都立即失效旧凭据）。 */
    default List<AiApplicationCredentialDO> selectActiveByApplication(Long applicationId) {
        return selectList(new LambdaQueryWrapperX<AiApplicationCredentialDO>()
                .eq(AiApplicationCredentialDO::getApplicationId, applicationId)
                .eq(AiApplicationCredentialDO::getStatus, AiApplicationCredentialDO.STATUS_ACTIVE)
                .orderByDesc(AiApplicationCredentialDO::getId));
    }

    /** 按摘要查可用凭据（换票校验用）。 */
    default AiApplicationCredentialDO selectActiveByDigest(String secretDigest) {
        return selectOne(new LambdaQueryWrapperX<AiApplicationCredentialDO>()
                .eq(AiApplicationCredentialDO::getSecretDigest, secretDigest)
                .eq(AiApplicationCredentialDO::getStatus, AiApplicationCredentialDO.STATUS_ACTIVE));
    }
}
