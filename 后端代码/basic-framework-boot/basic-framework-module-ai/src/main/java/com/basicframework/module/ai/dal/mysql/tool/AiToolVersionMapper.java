package com.basicframework.module.ai.dal.mysql.tool;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 工具版本 Mapper（D08）。 */
@Mapper
public interface AiToolVersionMapper extends BaseMapperX<AiToolVersionDO> {

    /** 工具内最新版本（无版本时返回 null）。 */
    default AiToolVersionDO selectLatest(Long toolId) {
        return selectOne(new LambdaQueryWrapperX<AiToolVersionDO>()
                .eq(AiToolVersionDO::getToolId, toolId)
                .orderByDesc(AiToolVersionDO::getVersionNo)
                .last("LIMIT 1"));
    }

    /** 按工具列出全部版本（版本号倒序）。 */
    default List<AiToolVersionDO> selectByTool(Long toolId) {
        return selectList(new LambdaQueryWrapperX<AiToolVersionDO>()
                .eq(AiToolVersionDO::getToolId, toolId)
                .orderByDesc(AiToolVersionDO::getVersionNo));
    }

    /** 分页（按编号倒序）。 */
    default PageResult<AiToolVersionDO> selectPage(PageParam pageParam, Long toolId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiToolVersionDO>()
                        .eqIfPresent(AiToolVersionDO::getToolId, toolId)
                        .eqIfPresent(AiToolVersionDO::getStatus, status)
                        .orderByDesc(AiToolVersionDO::getId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiToolVersionDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiToolVersionDO>()
                        .eq(AiToolVersionDO::getId, update.getId())
                        .eq(AiToolVersionDO::getVersion, expectedVersion));
    }
}
