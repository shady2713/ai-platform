package com.basicframework.module.ai.dal.dataobject.model;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 模型端点配置版本（不可变）。
 *
 * <p>写入后不允许修改或删除；凭据轮换不产生新版本，也不把秘密复制进历史。
 */
@TableName("ai_model_endpoint_revision")
@KeySequence("ai_model_endpoint_revision_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiModelEndpointRevisionDO extends SoftDeletableDO {

    /** 版本编号 */
    @TableId
    private Long id;

    /** 端点编号 */
    private Long endpointId;

    /** 非秘密配置版本（从 1 递增） */
    private Integer revision;

    /** 模型标识 */
    private String modelId;

    /** 能力集合（逗号分隔：TEXT,EMBEDDING） */
    private String capabilities;
}
