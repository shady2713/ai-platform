package com.basicframework.module.ai.dal.mysql.serviceconfig;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiServiceReleaseMapper extends BaseMapperX<AiServiceReleaseDO> {

    /** 服务的发布版本（倒序）。 */
    default List<AiServiceReleaseDO> selectByService(Long serviceId) {
        return selectList(new LambdaQueryWrapperX<AiServiceReleaseDO>()
                .eq(AiServiceReleaseDO::getServiceId, serviceId)
                .orderByDesc(AiServiceReleaseDO::getReleaseVersion));
    }
}
