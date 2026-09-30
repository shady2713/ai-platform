package com.basicframework.module.ai.dal.mysql.semantic;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 企业统一对象 Mapper（Y02）：标识唯一读取、分页与乐观锁 CAS。 */
@Mapper
public interface AiMasterObjectMapper extends BaseMapperX<AiMasterObjectDO> {

    /** 按标识唯一定位（未删除行；逻辑删除由框架条件保证）。 */
    default AiMasterObjectDO selectByCode(String objectCode) {
        if (objectCode == null) {
            return null;
        }
        return selectOne(new LambdaQueryWrapperX<AiMasterObjectDO>()
                .eq(AiMasterObjectDO::getObjectCode, objectCode)
                .last("limit 1"));
    }

    /** 管理端分页：按类型/状态过滤，关键字匹配标识或名称（都是控制面配置，非主体数据）。 */
    default PageResult<AiMasterObjectDO> selectPage(
            PageParam pageParam, String objectType, String status, String keyword) {
        LambdaQueryWrapperX<AiMasterObjectDO> wrapper = new LambdaQueryWrapperX<AiMasterObjectDO>()
                .eqIfPresent(AiMasterObjectDO::getObjectType, objectType)
                .eqIfPresent(AiMasterObjectDO::getStatus, status)
                .orderByDesc(AiMasterObjectDO::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(inner -> inner.like(AiMasterObjectDO::getObjectCode, keyword)
                    .or()
                    .like(AiMasterObjectDO::getObjectName, keyword));
        }
        return selectPage(pageParam, wrapper);
    }

    /** 乐观锁 CAS：名称/类别/状态与 current_revision 的更新只允许单赢家。 */
    default int updateWithVersion(AiMasterObjectDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiMasterObjectDO>()
                        .eq(AiMasterObjectDO::getId, update.getId())
                        .eq(AiMasterObjectDO::getVersion, expectedVersion));
    }
}
