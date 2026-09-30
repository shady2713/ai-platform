package com.basicframework.module.ai.dal.dataobject.semantic;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 跨源指标口径版本（Y03）：不可变的口径快照。
 *
 * <p>发布后 {@code definitionJson} 与 {@code definitionFingerprint} 不得再改（只能新建版本），
 * 因此旧报表按版本号引用时永远能取回"当时那份口径"。每次聚合前重算指纹并与冻结值比对，
 * 不符即阻断——这是"口径被版本外改动"能被发现的唯一手段。
 */
@TableName("ai_metric_semantics_revision")
@KeySequence("ai_metric_semantics_revision_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiMetricSemanticsRevisionDO extends SoftDeletableDO {

    /** 状态：草稿（可编辑） */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：已发布（不可变） */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 版本编号 */
    @TableId
    private Long id;

    /** 口径编号 */
    private Long metricSemanticsId;

    /** 口径版本号（口径内递增，发布后不可变） */
    private Long revisionNo;

    /** 状态（DRAFT/PUBLISHED） */
    private String status;

    /** 口径定义 JSON（币种/单位/时区/时间窗口/来源与主键粒度/聚合顺序/换算规则） */
    private String definitionJson;

    /** 发布时冻结的内容指纹（读取时重算比对） */
    private String definitionFingerprint;

    /** 版本有效期起点（含） */
    private LocalDateTime validFrom;

    /** 版本有效期终点（不含；null = 长期有效） */
    private LocalDateTime validTo;

    /** 草稿创建人（发布人必须不同：独立审核） */
    private Long createdBy;

    /** 发布人（必须与草稿创建人不同） */
    private Long publishedBy;

    /** 发布时间 */
    private LocalDateTime publishedTime;

    /** 乐观锁版本 */
    private Integer version;
}
