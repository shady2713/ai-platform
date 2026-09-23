package com.basicframework.module.ai.dal.mysql.report;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 报表版本 Mapper（R04）。 */
@Mapper
public interface AiReportVersionMapper extends BaseMapperX<AiReportVersionDO> {

    /** 按报表与版本号定位。 */
    default AiReportVersionDO selectByVersionNo(Long reportId, Integer versionNo) {
        return selectOne(new LambdaQueryWrapperX<AiReportVersionDO>()
                .eq(AiReportVersionDO::getReportId, reportId)
                .eq(AiReportVersionDO::getVersionNo, versionNo));
    }

    /** 某报表的版本（版本号倒序）。 */
    default List<AiReportVersionDO> selectByReport(Long reportId) {
        return selectList(new LambdaQueryWrapperX<AiReportVersionDO>()
                .eq(AiReportVersionDO::getReportId, reportId)
                .orderByDesc(AiReportVersionDO::getVersionNo));
    }
}
