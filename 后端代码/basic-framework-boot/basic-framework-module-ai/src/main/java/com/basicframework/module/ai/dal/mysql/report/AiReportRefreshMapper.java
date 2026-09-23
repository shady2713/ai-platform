package com.basicframework.module.ai.dal.mysql.report;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.report.AiReportRefreshDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 报表刷新尝试 Mapper（R06）：按报表取最近一次尝试 / 最近一次成功，并扫描"到期未刷新"的可刷新报表。 */
@Mapper
public interface AiReportRefreshMapper extends BaseMapperX<AiReportRefreshDO> {

    /** 最近一次尝试（按编号倒序）。 */
    default AiReportRefreshDO selectLastByReport(Long reportId) {
        return selectOne(new LambdaQueryWrapperX<AiReportRefreshDO>()
                .eq(AiReportRefreshDO::getReportId, reportId)
                .orderByDesc(AiReportRefreshDO::getId)
                .last("LIMIT 1"));
    }

    /** 最近一次成功刷新（读取"上一次结果"用）。 */
    default AiReportRefreshDO selectLastOkByReport(Long reportId) {
        return selectOne(new LambdaQueryWrapperX<AiReportRefreshDO>()
                .eq(AiReportRefreshDO::getReportId, reportId)
                .eq(AiReportRefreshDO::getStatus, AiReportRefreshDO.STATUS_OK)
                .orderByDesc(AiReportRefreshDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 扫描"到期未刷新"的可刷新报表：最近一次尝试（无论成功或失败）早于 {@code intervalSeconds} 秒。
     *
     * <p>为什么把失败尝试也算作"近期尝试"：失败原因多是环境/权限问题，按同一节奏重试即可；
     * 否则失败报表会被每一轮扫描重复尝试，把尝试记录刷成噪声（AT-047 要的是"失败留痕"，不是"失败风暴"）。
     */
    @Select("SELECT r.id AS reportId FROM ai_report r"
            + " WHERE r.deleted = b'0' AND r.mode = 'REFRESHABLE'"
            + " AND NOT EXISTS (SELECT 1 FROM ai_report_refresh f"
            + "   WHERE f.report_id = r.id AND f.deleted = b'0'"
            + "     AND f.as_of > DATE_SUB(NOW(), INTERVAL #{intervalSeconds} SECOND))"
            + " ORDER BY r.id LIMIT #{limit}")
    List<Long> selectDueReportIds(@Param("intervalSeconds") int intervalSeconds, @Param("limit") int limit);
}
