package com.basicframework.module.ai.dal.mysql.crosssource;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionSourceDO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 跨源来源贡献 Mapper（Y04）：**重试去重的写侧机制**。
 *
 * <p>两个方法构成幂等的全部：
 * <ul>
 *   <li>{@code insertCounted}：唯一键 {@code (execution_id, role, deleted)} 让"每来源一行"
 *       成为数据库不变量。并发或重试下的第二次插入由唯一键挡住，调用方据此判定"已计入"；</li>
 *   <li>{@code overwriteCountedWithVersion}：带乐观锁的覆盖（不是追加）。
 *       来源重取后金额可能变化（例如补数），正确语义是"用新值替换旧值"，
 *       绝不是在旧值上再叠一次——叠加会让重试后的合计随重试次数线性膨胀。</li>
 * </ul>
 */
@Mapper
public interface AiCrossSourceSourceContributionMapper extends BaseMapperX<AiCrossSourceExecutionSourceDO> {

    /** 按执行编号 + 来源角色定位那一行（唯一键定位；未计入返回 null）。 */
    default AiCrossSourceExecutionSourceDO selectByRole(Long executionId, String role) {
        return selectOne(new LambdaQueryWrapperX<AiCrossSourceExecutionSourceDO>()
                .eq(AiCrossSourceExecutionSourceDO::getExecutionId, executionId)
                .eq(AiCrossSourceExecutionSourceDO::getRole, role)
                .last("limit 1"));
    }

    /**
     * 某次执行**已计入**的来源行（合计由此求和）。
     *
     * <p>只取 {@code status='COUNTED'}：失败与缺失的行不进合计——缺失不是 0，
     * 把 MISSING 行也求和会让"少了一个来源"看起来像"那个来源算出来是 0"。
     * 每个来源在唯一键下最多一行，因此这份结果与重试次数无关。
     */
    @Select(
            """
            SELECT id, execution_id, role, dataset_code, dataset_version, mapping_revision, status,
                   amount, row_count, byte_size, source_as_of, elapsed_millis, attempt_count, version,
                   creator, create_time, updater, update_time, deleted
            FROM ai_cross_source_execution_source
            WHERE execution_id = #{executionId}
              AND status = 'COUNTED'
              AND deleted = b'0'
            ORDER BY role
            """)
    List<AiCrossSourceExecutionSourceDO> selectCountedSources(@Param("executionId") Long executionId);

    /**
     * 计入一个来源（CAS 覆盖：命中返回 1，未命中返回 0）。
     *
     * <p>覆盖而非累加是重试幂等的关键：{@code amount = #{amount}} 是赋值，
     * 因此同一个来源无论重试多少次，库里始终只有一份金额。
     * 未命中（返回 0）意味着并发下别人已经改过这一行，调用方按"重新读取后判定"处理。
     *
     * <p><b>同时把 status 置回 {@code COUNTED}</b>：这一步让"上一次取不到、重试后取到了"
     * 的来源能真正回到合计里。若只更新金额而留着 {@code MISSING}/{@code FAILED}，
     * {@code selectCountedSources} 会永远排除它，重试就只补了数据却补不进合计。
     */
    @Update(
            """
            UPDATE ai_cross_source_execution_source
               SET status = 'COUNTED',
                   amount = #{amount},
                   row_count = #{rowCount},
                   byte_size = #{byteSize},
                   source_as_of = #{sourceAsOf},
                   elapsed_millis = #{elapsedMillis},
                   attempt_count = attempt_count + 1,
                   version = version + 1,
                   update_time = CURRENT_TIMESTAMP
             WHERE execution_id = #{executionId}
               AND role = #{role}
               AND version = #{version}
               AND deleted = b'0'
            """)
    int overwriteCountedWithVersion(
            @Param("executionId") Long executionId,
            @Param("role") String role,
            @Param("amount") BigDecimal amount,
            @Param("rowCount") Integer rowCount,
            @Param("byteSize") Long byteSize,
            @Param("sourceAsOf") LocalDateTime sourceAsOf,
            @Param("elapsedMillis") Long elapsedMillis,
            @Param("version") Integer version);

    /**
     * 标记一个来源失败或缺失（同样带乐观锁，且不改动已计入的金额）。
     *
     * <p>失败与缺失都留下行：留行才能让"缺了哪个来源"在结果里可查，
     * 只有成功才写行的话，缺行与"这个来源本来就不存在"无法区分。
     */
    @Update(
            """
            UPDATE ai_cross_source_execution_source
               SET status = #{status},
                   attempt_count = attempt_count + 1,
                   version = version + 1,
                   update_time = CURRENT_TIMESTAMP
             WHERE execution_id = #{executionId}
               AND role = #{role}
               AND version = #{version}
               AND deleted = b'0'
            """)
    int markStatusWithVersion(
            @Param("executionId") Long executionId,
            @Param("role") String role,
            @Param("status") String status,
            @Param("version") Integer version);
}
