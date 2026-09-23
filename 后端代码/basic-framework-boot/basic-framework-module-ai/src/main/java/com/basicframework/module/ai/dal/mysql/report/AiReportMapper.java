package com.basicframework.module.ai.dal.mysql.report;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import org.apache.ibatis.annotations.Mapper;

/** 报表 Mapper（R04）。 */
@Mapper
public interface AiReportMapper extends BaseMapperX<AiReportDO> {

    /** 按应用与标识定位（应用内唯一）。 */
    default AiReportDO selectByCode(Long applicationId, String code) {
        return selectOne(new LambdaQueryWrapperX<AiReportDO>()
                .eq(AiReportDO::getApplicationId, applicationId)
                .eq(AiReportDO::getCode, code));
    }

    /** 分页：只返回当前主体的报表（私人报表按归属过滤）。 */
    default PageResult<AiReportDO> selectPage(
            PageParam pageParam, Long applicationId, String subjectType, String externalUserId, String mode) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiReportDO>()
                        .eq(AiReportDO::getApplicationId, applicationId)
                        .eq(AiReportDO::getSubjectType, subjectType)
                        .eq(AiReportDO::getExternalUserId, externalUserId == null ? "" : externalUserId)
                        .eqIfPresent(AiReportDO::getMode, mode)
                        .orderByDesc(AiReportDO::getId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiReportDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiReportDO>()
                        .eq(AiReportDO::getId, update.getId())
                        .eq(AiReportDO::getVersion, expectedVersion));
    }
}
