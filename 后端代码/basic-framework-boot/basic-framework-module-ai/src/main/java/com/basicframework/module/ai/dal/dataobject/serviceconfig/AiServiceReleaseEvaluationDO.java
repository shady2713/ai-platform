package com.basicframework.module.ai.dal.dataobject.serviceconfig;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 服务发布评测记录（S02）：评测结论绑定**内容摘要 + 端点配置版本**，写入后不再修改。
 *
 * <p>{@code passed} 由平台按评测时冻结的 {@code threshold} 判定，调用方只能提交得分与用例数，
 * 不能直接提交结论；发布预检查读取该版本的**最新一条**结论，任何"先通过、后失败"的序列
 * 都会以最后一条为准。
 */
@TableName("ai_service_release_evaluation")
@KeySequence("ai_service_release_evaluation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiServiceReleaseEvaluationDO extends SoftDeletableDO {

    /** 评测记录编号 */
    @TableId
    private Long id;

    /** 服务编号（冗余，便于按服务追溯） */
    private Long serviceId;

    /** 发布版本编号 */
    private Long releaseId;

    /** 被评测内容摘要（须与发布版本内容摘要一致才有效） */
    private String contentHash;

    /** 评测所用模型端点编号 */
    private Long modelEndpointId;

    /** 评测所用端点配置版本 */
    private Integer endpointConfigRevision;

    /** 评测得分（0-100） */
    private Integer score;

    /** 评测时冻结的门槛（得分 >= 门槛 才通过） */
    private Integer threshold;

    /** 是否通过（平台判定） */
    private Boolean passed;

    /** 评测用例数（至少 1） */
    private Integer caseCount;

    /** 备注（不得写入提示词、响应正文或凭据） */
    private String notes;

    /** 乐观锁版本 */
    private Integer version;
}
