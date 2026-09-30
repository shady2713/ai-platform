package com.basicframework.module.ai.dal.mysql.semantic;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 主数据映射版本 Mapper（Y02）：按对象+版本号唯一定位、最大版本号、发布 CAS。 */
@Mapper
public interface AiMasterObjectRevisionMapper extends BaseMapperX<AiMasterObjectRevisionDO> {

    /** 按对象 + 版本号唯一定位（判定只按显式版本读取，不回退最新）。 */
    default AiMasterObjectRevisionDO selectByRevisionNo(Long masterObjectId, Long revisionNo) {
        if (masterObjectId == null || revisionNo == null) {
            return null;
        }
        return selectOne(new LambdaQueryWrapperX<AiMasterObjectRevisionDO>()
                .eq(AiMasterObjectRevisionDO::getMasterObjectId, masterObjectId)
                .eq(AiMasterObjectRevisionDO::getRevisionNo, revisionNo)
                .last("limit 1"));
    }

    /** 对象内最大版本号（新草稿的编号 = 最大值 + 1；无版本时返回 null）。 */
    default AiMasterObjectRevisionDO selectLatest(Long masterObjectId) {
        if (masterObjectId == null) {
            return null;
        }
        return selectOne(new LambdaQueryWrapperX<AiMasterObjectRevisionDO>()
                .eq(AiMasterObjectRevisionDO::getMasterObjectId, masterObjectId)
                .orderByDesc(AiMasterObjectRevisionDO::getRevisionNo)
                .last("limit 1"));
    }

    /** 对象的版本分页（版本号倒序）。 */
    default PageResult<AiMasterObjectRevisionDO> selectPage(PageParam pageParam, Long masterObjectId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiMasterObjectRevisionDO>()
                        .eqIfPresent(AiMasterObjectRevisionDO::getMasterObjectId, masterObjectId)
                        .eqIfPresent(AiMasterObjectRevisionDO::getStatus, status)
                        .orderByDesc(AiMasterObjectRevisionDO::getRevisionNo));
    }

    /** 乐观锁 CAS：发布（写入指纹与摘要）只允许单赢家。 */
    default int updateWithVersion(AiMasterObjectRevisionDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiMasterObjectRevisionDO>()
                        .eq(AiMasterObjectRevisionDO::getId, update.getId())
                        .eq(AiMasterObjectRevisionDO::getVersion, expectedVersion));
    }
}
