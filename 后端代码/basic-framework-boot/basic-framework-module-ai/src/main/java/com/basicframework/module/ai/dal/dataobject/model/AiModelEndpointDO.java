package com.basicframework.module.ai.dal.dataobject.model;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 模型端点。
 *
 * <p>非秘密配置（模型标识、能力）通过 {@link AiModelEndpointRevisionDO} 生成不可变版本；
 * 凭据只保存在本表 {@code credentialCiphertext}（CredentialCipher 密文），轮换只递增
 * {@code credentialRevision}，历史版本不含秘密。provider/baseUrl 被发布服务引用后不可原地修改。
 */
@TableName("ai_model_endpoint")
@KeySequence("ai_model_endpoint_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiModelEndpointDO extends SoftDeletableDO {

    /** 端点编号 */
    @TableId
    private Long id;

    /** 端点名称（唯一） */
    private String name;

    /** 提供方标识，例如 openai_compatible */
    private String provider;

    /** 基础地址（https） */
    private String baseUrl;

    /** 当前非秘密配置版本 */
    private Integer configRevision;

    /** 凭据版本（0 表示未配置）；不得进入日志 */
    @ToString.Exclude
    private Integer credentialRevision;

    /** 凭据密文（AES-GCM，含版本前缀）；查询接口永不返回该字段，且不得进入日志 */
    @ToString.Exclude
    private String credentialCiphertext;

    /** 是否启用 */
    private Boolean enabled;

    /** 是否被发布服务引用（引用后 provider/baseUrl 不可原地修改） */
    private Boolean referenced;

    /** 已记录的嵌入维度（首次成功嵌入时落库；改变即拒绝写入既有索引） */
    private Integer embeddingDimension;

    /** 乐观锁版本（手工 CAS） */
    private Integer version;
}
