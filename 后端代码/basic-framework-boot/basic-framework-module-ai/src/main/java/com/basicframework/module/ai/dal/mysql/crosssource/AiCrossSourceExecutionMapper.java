package com.basicframework.module.ai.dal.mysql.crosssource;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 跨源执行记录 Mapper（Y04）：执行键幂等定位与终态 CAS 流转。
 *
 * <p>只有真正会被执行路径调用的方法才有实现：按执行键定位与终态 CAS。
 * 来源贡献的读写在 {@link AiCrossSourceSourceContributionMapper}——两类表各有各的
 * 不变量（执行记录管"一次执行"，来源台账管"每来源一行"），不混在一个 Mapper 里。
 *
 * <p>CAS 覆盖"命中"与"未命中"两侧：{@code updateStatusWithVersion} 返回 0 就是"未命中"，
 * 服务层据此判定并发冲突（并发收尾只有一个赢家）。
 */
@Mapper
public interface AiCrossSourceExecutionMapper extends BaseMapperX<AiCrossSourceExecutionDO> {

    /** 按执行幂等键定位（唯一索引定位；不存在返回 null，不抛"查无此项"）。 */
    default AiCrossSourceExecutionDO selectByExecutionKey(String executionKey) {
        return selectOne(new LambdaQueryWrapperX<AiCrossSourceExecutionDO>()
                .eq(AiCrossSourceExecutionDO::getExecutionKey, executionKey)
                .last("limit 1"));
    }

    /**
     * 终态流转 CAS（命中返回 1，未命中返回 0）。
     *
     * <p>只允许从 RUNNING 流转到终态：把 {@code fromStatus} 写进条件而不是先查后写，
     * 是为了让"两个线程同时把同一次执行收尾"只有一个赢家，另一个拿到 0 并按冲突处理。
     */
    @Update(
            """
            UPDATE ai_cross_source_execution
               SET status = #{toStatus},
                   total_amount = #{totalAmount},
                   currency = #{currency},
                   consistency_as_of = #{consistencyAsOf},
                   max_skew_millis = #{maxSkewMillis},
                   missing_roles = #{missingRoles},
                   total_bytes = #{totalBytes},
                   total_rows = #{totalRows},
                   concurrent_peak = #{concurrentPeak},
                   failure_code = #{failureCode},
                   version = version + 1,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND status = #{fromStatus}
               AND version = #{version}
               AND deleted = b'0'
            """)
    int updateStatusWithVersion(
            @Param("id") Long id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("totalAmount") java.math.BigDecimal totalAmount,
            @Param("currency") String currency,
            @Param("consistencyAsOf") java.time.LocalDateTime consistencyAsOf,
            @Param("maxSkewMillis") Long maxSkewMillis,
            @Param("missingRoles") String missingRoles,
            @Param("totalBytes") Long totalBytes,
            @Param("totalRows") Integer totalRows,
            @Param("concurrentPeak") Integer concurrentPeak,
            @Param("failureCode") Integer failureCode,
            @Param("version") Integer version);
}
