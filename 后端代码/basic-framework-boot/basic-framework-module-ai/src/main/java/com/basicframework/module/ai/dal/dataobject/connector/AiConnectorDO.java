package com.basicframework.module.ai.dal.dataobject.connector;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 连接器（D01）：声明式配置 + 加密秘密。
 *
 * <p>{@code configJson} 只保存结构化字段（不含秘密）；秘密只以 {@link #credentialCiphertext} 密文形式存在，
 * 查询接口永不返回该字段，只回"是否已配置"。{@code referenced} 标记被数据集/工具引用，
 * 引用中的连接器不可删除。
 */
@TableName("ai_connector")
@KeySequence("ai_connector_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiConnectorDO extends SoftDeletableDO {

    /** 类型：HTTP 接口 */
    public static final String TYPE_HTTP = "HTTP";

    /** 类型：MySQL 只读连接 */
    public static final String TYPE_MYSQL = "MYSQL";

    /** 状态：启用 */
    public static final String STATUS_ENABLED = "ENABLED";

    /** 状态：停用 */
    public static final String STATUS_DISABLED = "DISABLED";

    /** 连接器编号 */
    @TableId
    private Long id;

    /** 连接器标识（全局唯一，创建后不可修改） */
    private String code;

    /** 连接器名称 */
    private String name;

    /** 类型（HTTP/MYSQL） */
    private String connectorType;

    /** 状态（ENABLED/DISABLED） */
    private String status;

    /** 声明式配置（结构化字段，不含秘密） */
    private String configJson;

    /** 秘密密文（AES-GCM；查询接口永不返回，且不得进入日志） */
    @ToString.Exclude
    private String credentialCiphertext;

    /** 秘密版本（0 表示未配置；不进 toString，避免暴露秘密相关元数据） */
    @ToString.Exclude
    private Integer credentialRevision;

    /** 是否被数据集/工具引用（引用后不可删除） */
    private Boolean referenced;

    /** 乐观锁版本 */
    private Integer version;
}
