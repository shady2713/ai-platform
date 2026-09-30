package com.basicframework.module.ai.dal.dataobject.crosssource;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 跨源执行的来源贡献（Y04）：**"已计入"标记的物理载体**。
 *
 * <p>重试幂等的全部机制都在这张表上：
 * <ul>
 *   <li>唯一键 {@code (execution_id, role, deleted)} —— 一个来源在一次执行里最多只有一行。
 *       重复计入在**数据库层**就被挡住，不依赖任何应用层的"我记着上次加过了"；</li>
 *   <li>{@code counted} —— 显式的已计入标记。合计由"已计入的行"求和得到，
 *       因此重试把某来源重新取一遍时，只会覆盖该行而不会多出一行；</li>
 *   <li>{@code attemptCount} —— 重试次数可观测：调用方能看见"这个来源重试了两次才成功"，
 *       而不是只看到一个最终数字。</li>
 * </ul>
 *
 * <p>没有这张表就只能靠内存里的 Map 去重——进程一重启就丢，重试跨进程时重复汇总。
 * 这正是本卡把"重试幂等要有真实机制"落到持久层的原因。
 */
@TableName("ai_cross_source_execution_source")
@KeySequence("ai_cross_source_execution_source_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiCrossSourceExecutionSourceDO extends SoftDeletableDO {

    /** 状态：已计入合计。 */
    public static final String STATUS_COUNTED = "COUNTED";

    /** 状态：必需来源失败（整体受控结束）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 状态：可选来源缺失（结果显式标注不完整）。 */
    public static final String STATUS_MISSING = "MISSING";

    /** 贡献编号 */
    @TableId
    private Long id;

    /** 跨源执行编号 */
    private Long executionId;

    /** 来源角色（与唯一键共同构成"每来源一行"） */
    private String role;

    /** 数据集标识 */
    private String datasetCode;

    /** 数据集版本号 */
    private Integer datasetVersion;

    /** 该来源钉住的实体键映射版本 */
    private Long mappingRevision;

    /** 状态（COUNTED/FAILED/MISSING） */
    private String status;

    /** 已计入的金额（唯一一份；重试覆盖而不叠加） */
    private BigDecimal amount;

    /** 该来源返回的中间结果行数 */
    private Integer rowCount;

    /** 该来源中间结果字节 */
    private Long byteSize;

    /** **该来源自己的**数据时间点（不是执行时刻，也不是其它来源的时间点） */
    private LocalDateTime sourceAsOf;

    /** 该来源耗时（毫秒） */
    private Long elapsedMillis;

    /** 取数尝试次数（含重试；>1 说明该来源重试过） */
    private Integer attemptCount;

    /** 乐观锁版本（重试覆盖用：并发重试只有一个赢家） */
    private Integer version;
}
