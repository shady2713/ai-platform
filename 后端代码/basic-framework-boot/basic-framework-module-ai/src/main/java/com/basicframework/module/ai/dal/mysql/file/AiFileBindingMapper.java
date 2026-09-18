package com.basicframework.module.ai.dal.mysql.file;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.file.AiFileBindingDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiFileBindingMapper extends BaseMapperX<AiFileBindingDO> {

    /** 某文件的有效引用。 */
    default List<AiFileBindingDO> selectActiveByFile(Long fileId) {
        return selectList(new LambdaQueryWrapperX<AiFileBindingDO>()
                .eq(AiFileBindingDO::getFileId, fileId)
                .eq(AiFileBindingDO::getStatus, AiFileBindingDO.STATUS_ACTIVE));
    }

    /** 某业务对象（类型 + 标识）的有效引用。 */
    default List<AiFileBindingDO> selectActiveByBusiness(String businessType, String businessKey) {
        return selectList(new LambdaQueryWrapperX<AiFileBindingDO>()
                .eq(AiFileBindingDO::getBusinessType, businessType)
                .eq(AiFileBindingDO::getBusinessKey, businessKey)
                .eq(AiFileBindingDO::getStatus, AiFileBindingDO.STATUS_ACTIVE));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiFileBindingDO update, Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiFileBindingDO>()
                        .eq(AiFileBindingDO::getId, update.getId())
                        .eq(AiFileBindingDO::getVersion, expectedVersion));
    }
}
