package com.basicframework.module.ai.dal.mysql.semantic;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMetricSemanticsDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 跨源指标口径 Mapper（Y03）：标识唯一读取、分页与乐观锁 CAS。 */
@Mapper
public interface AiMetricSemanticsMapper extends BaseMapperX<AiMetricSemanticsDO> {

    /** 按标识唯一定位（未删除行；逻辑删除由框架条件保证）。 */
    default AiMetricSemanticsDO selectByCode(String metricCode) {
        if (metricCode == null) {
            return null;
        }
        return selectOne(new LambdaQueryWrapperX<AiMetricSemanticsDO>()
                .eq(AiMetricSemanticsDO::getMetricCode, metricCode)
                .last("limit 1"));
    }

    /** 管理端分页：按状态过滤，关键字匹配标识或名称（都是控制面配置，非业务数据）。 */
    default PageResult<AiMetricSemanticsDO> selectPage(PageParam pageParam, String status, String keyword) {
        LambdaQueryWrapperX<AiMetricSemanticsDO> wrapper = new LambdaQueryWrapperX<AiMetricSemanticsDO>()
                .eqIfPresent(AiMetricSemanticsDO::getStatus, status)
                .orderByDesc(AiMetricSemanticsDO::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(inner -> inner.like(AiMetricSemanticsDO::getMetricCode, keyword)
                    .or()
                    .like(AiMetricSemanticsDO::getMetricName, keyword));
        }
        return selectPage(pageParam, wrapper);
    }

    /** 乐观锁 CAS：状态与 current_revision 的更新只允许单赢家。 */
    default int updateWithVersion(AiMetricSemanticsDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiMetricSemanticsDO>()
                        .eq(AiMetricSemanticsDO::getId, update.getId())
                        .eq(AiMetricSemanticsDO::getVersion, expectedVersion));
    }
}
