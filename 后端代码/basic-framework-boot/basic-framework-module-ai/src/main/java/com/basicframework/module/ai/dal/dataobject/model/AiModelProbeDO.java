package com.basicframework.module.ai.dal.dataobject.model;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 模型能力探测结果（M04）。
 *
 * <p>每次探测写一行，记录**真实调用**得到的稳定结论：状态、稳定明细码、嵌入维度与耗时。
 * 只存结论不存上游报文：失败明细是 {@code ModelException.Reason} 名称，不含凭据、提示词或响应正文。
 */
@TableName("ai_model_probe")
@KeySequence("ai_model_probe_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiModelProbeDO extends SoftDeletableDO {

    /** 探测记录编号 */
    @TableId
    private Long id;

    /** 端点编号 */
    private Long endpointId;

    /** 探测时的配置版本（版本变化后可重新探测并对比） */
    private Integer configRevision;

    /** 探测时的凭据版本（只记录版本号，不含凭据） */
    @ToString.Exclude
    private Integer credentialRevision;

    /** 探测类型（ModelProbeKind 名称） */
    private String probeKind;

    /** 结论状态（SUPPORTED/UNSUPPORTED/FAILED） */
    private String status;

    /** 稳定明细码：失败原因名或不支持原因；成功为空 */
    private String detailCode;

    /** 嵌入探测观测到的向量维度 */
    private Integer embeddingDimension;

    /** 真实调用耗时（毫秒） */
    private Integer latencyMs;
}
