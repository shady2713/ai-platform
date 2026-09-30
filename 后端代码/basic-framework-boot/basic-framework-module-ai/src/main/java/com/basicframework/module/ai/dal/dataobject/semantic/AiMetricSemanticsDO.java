package com.basicframework.module.ai.dal.dataobject.semantic;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 跨源指标口径（Y03）：跨源聚合的登记锚点。
 *
 * <p>三条不变量：
 * <ul>
 *   <li><b>标识稳定且不可修改</b>：{@code metric_code} 是平台内唯一标识，登记后只能改名称/说明/状态
 *       （改标识会让历史报表指向另一个口径）；</li>
 *   <li><b>聚合必须显式指定版本</b>：{@code current_revision} 只表示"当前已发布的最新版本"，
 *       聚合路径不接受"取最新"的省略写法，旧报表/旧产物按受理时的版本编号解释；</li>
 *   <li><b>停用即阻断</b>：{@code status = DISABLED} 时任何跨源聚合都被拒绝，不回退到历史版本。</li>
 * </ul>
 */
@TableName("ai_metric_semantics")
@KeySequence("ai_metric_semantics_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiMetricSemanticsDO extends SoftDeletableDO {

    /** 状态：可用。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：停用（跨源聚合阻断）。 */
    public static final String STATUS_DISABLED = "DISABLED";

    /** 口径编号 */
    @TableId
    private Long id;

    /** 口径标识（全局唯一，稳定且不可修改） */
    private String metricCode;

    /** 口径名称（仅展示） */
    private String metricName;

    /** 说明 */
    private String description;

    /** 状态（ACTIVE/DISABLED） */
    private String status;

    /** 当前已发布的口径版本（0=尚无已发布版本） */
    private Long currentRevision;

    /** 乐观锁版本 */
    private Integer version;
}
