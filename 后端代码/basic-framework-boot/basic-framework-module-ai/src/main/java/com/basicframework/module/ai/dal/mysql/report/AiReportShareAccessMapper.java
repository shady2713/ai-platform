package com.basicframework.module.ai.dal.mysql.report;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareAccessDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 报表分享访问审计 Mapper（X11，append-retention：只插入与按分享读取）。 */
@Mapper
public interface AiReportShareAccessMapper extends BaseMapperX<AiReportShareAccessDO> {

    /** 某分享的访问记录（编号倒序：最新的读取排最前，供授予者审计）。 */
    default List<AiReportShareAccessDO> selectByShare(Long shareId) {
        return selectList(new LambdaQueryWrapperX<AiReportShareAccessDO>()
                .eq(AiReportShareAccessDO::getShareId, shareId)
                .orderByDesc(AiReportShareAccessDO::getId));
    }
}
