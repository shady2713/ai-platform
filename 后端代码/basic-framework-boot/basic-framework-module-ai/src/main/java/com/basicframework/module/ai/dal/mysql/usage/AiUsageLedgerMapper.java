package com.basicframework.module.ai.dal.mysql.usage;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.usage.AiUsageLedgerDO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 用量账本 Mapper（Q02）。 */
@Mapper
public interface AiUsageLedgerMapper extends BaseMapperX<AiUsageLedgerDO> {

    /** 按调用标识定位（唯一键；重复写入时用来判断"是否已记过"）。 */
    default AiUsageLedgerDO selectByInvocation(String invocationId) {
        return selectOne(new LambdaQueryWrapperX<AiUsageLedgerDO>().eq(AiUsageLedgerDO::getInvocationId, invocationId));
    }

    /** 按运行聚合。 */
    default List<AiUsageLedgerDO> selectByRun(Long runId) {
        return selectList(new LambdaQueryWrapperX<AiUsageLedgerDO>().eq(AiUsageLedgerDO::getRunId, runId));
    }

    /** 分页（按应用/服务/时间窗过滤，倒序）。 */
    default PageResult<AiUsageLedgerDO> selectPage(
            PageParam pageParam, Long applicationId, Long serviceId, LocalDateTime from, LocalDateTime to) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiUsageLedgerDO>()
                        .eqIfPresent(AiUsageLedgerDO::getApplicationId, applicationId)
                        .eqIfPresent(AiUsageLedgerDO::getServiceId, serviceId)
                        .geIfPresent(AiUsageLedgerDO::getOccurredAt, from)
                        .leIfPresent(AiUsageLedgerDO::getOccurredAt, to)
                        .orderByDesc(AiUsageLedgerDO::getId));
    }

    /** 按应用 + 时间窗聚合 token（未知来源不计入 token 合计，只计条数与来源分布）。 */
    @Select(
            """
            SELECT usage_source AS usageSource,
                   COUNT(*) AS invocationCount,
                   COALESCE(SUM(input_tokens), 0) AS inputTokens,
                   COALESCE(SUM(output_tokens), 0) AS outputTokens
            FROM ai_usage_ledger
            WHERE application_id = #{applicationId}
              AND occurred_at >= #{from} AND occurred_at < #{to}
            GROUP BY usage_source
            """)
    List<Map<String, Object>> aggregateBySource(
            @Param("applicationId") Long applicationId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 按服务聚合。 */
    @Select(
            """
            SELECT service_id AS serviceId,
                   COUNT(*) AS invocationCount,
                   COALESCE(SUM(input_tokens), 0) AS inputTokens,
                   COALESCE(SUM(output_tokens), 0) AS outputTokens
            FROM ai_usage_ledger
            WHERE application_id = #{applicationId}
              AND occurred_at >= #{from} AND occurred_at < #{to}
            GROUP BY service_id
            """)
    List<Map<String, Object>> aggregateByService(
            @Param("applicationId") Long applicationId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
