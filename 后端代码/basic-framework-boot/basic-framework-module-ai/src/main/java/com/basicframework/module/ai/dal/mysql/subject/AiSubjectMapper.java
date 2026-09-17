package com.basicframework.module.ai.dal.mysql.subject;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiSubjectMapper extends BaseMapperX<AiSubjectDO> {

    /** 按唯一键查询主体：同一 externalUserId 在不同应用下是不同主体。 */
    default AiSubjectDO selectByIdentity(Long applicationId, String subjectType, String externalUserId) {
        return selectOne(new LambdaQueryWrapperX<AiSubjectDO>()
                .eq(AiSubjectDO::getApplicationId, applicationId)
                .eq(AiSubjectDO::getSubjectType, subjectType)
                .eq(AiSubjectDO::getExternalUserId, externalUserId == null ? "" : externalUserId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiSubjectDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiSubjectDO>()
                        .eq(AiSubjectDO::getId, update.getId())
                        .eq(AiSubjectDO::getVersion, expectedVersion));
    }
}
