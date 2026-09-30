package com.basicframework.module.ai.dal.mysql.semantic;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMetricSemanticsRevisionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 跨源指标口径版本 Mapper（Y03）：版本定位、草稿 CAS 与分页。 */
@Mapper
public interface AiMetricSemanticsRevisionMapper extends BaseMapperX<AiMetricSemanticsRevisionDO> {

    /** 按（口径, 版本号）唯一定位；两个入参缺一即返回 null（不允许"只给口径取最新版"）。 */
    default AiMetricSemanticsRevisionDO selectByRevisionNo(Long metricSemanticsId, Long revisionNo) {
        if (metricSemanticsId == null || revisionNo == null) {
            return null;
        }
        return selectOne(new LambdaQueryWrapperX<AiMetricSemanticsRevisionDO>()
                .eq(AiMetricSemanticsRevisionDO::getMetricSemanticsId, metricSemanticsId)
                .eq(AiMetricSemanticsRevisionDO::getRevisionNo, revisionNo)
                .last("limit 1"));
    }

    /** 管理端分页：按口径与状态过滤。 */
    default PageResult<AiMetricSemanticsRevisionDO> selectPage(
            PageParam pageParam, Long metricSemanticsId, String status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiMetricSemanticsRevisionDO>()
                        .eqIfPresent(AiMetricSemanticsRevisionDO::getMetricSemanticsId, metricSemanticsId)
                        .eqIfPresent(AiMetricSemanticsRevisionDO::getStatus, status)
                        .orderByDesc(AiMetricSemanticsRevisionDO::getId));
    }

    /** 乐观锁 CAS：发布动作只允许单赢家（同时推进发布态与冻结指纹）。 */
    default int publishWithVersion(
            AiMetricSemanticsRevisionDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiMetricSemanticsRevisionDO>()
                        .eq(AiMetricSemanticsRevisionDO::getId, update.getId())
                        .eq(AiMetricSemanticsRevisionDO::getVersion, expectedVersion));
    }

    /** 草稿期更新定义（乐观锁 CAS；已发布版本必须先被服务层拒绝才到这里）。 */
    default int updateDefinitionWithVersion(
            AiMetricSemanticsRevisionDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiMetricSemanticsRevisionDO>()
                        .eq(AiMetricSemanticsRevisionDO::getId, update.getId())
                        .eq(AiMetricSemanticsRevisionDO::getVersion, expectedVersion));
    }
}
