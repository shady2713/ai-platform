package com.basicframework.module.ai.dal.dataobject.serviceconfig;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 服务发布版本（S01 建表，S02 写入）：发布时固定配置与端点配置版本，写入后不可修改。
 *
 * <p>{@code contentHash} 覆盖发布内容（提示词、Schema、能力、端点与端点配置版本），
 * 评测与回退都以它为稳定标识。
 */
@TableName("ai_service_release")
@KeySequence("ai_service_release_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiServiceReleaseDO extends SoftDeletableDO {

    /** 状态：候选（可测试与评测，尚未对业务开放） */
    public static final String STATUS_CANDIDATE = "CANDIDATE";

    /** 状态：当前生效 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已退役 */
    public static final String STATUS_RETIRED = "RETIRED";

    /** 发布版本编号 */
    @TableId
    private Long id;

    /** 服务编号 */
    private Long serviceId;

    /** 发布版本号（从 1 递增，不可变） */
    private Integer releaseVersion;

    /** 发布时固定的模型端点编号 */
    private Long modelEndpointId;

    /** 发布时固定的端点配置版本 */
    private Integer endpointConfigRevision;

    /** 发布时固定的提示词模板 */
    private String promptTemplate;

    /** 发布时固定的输入 Schema */
    private String inputSchema;

    /** 发布时固定的输出 Schema */
    private String outputSchema;

    /** 发布时固定的能力集合 */
    private String requiredCapabilities;

    /** 发布内容摘要 */
    private String contentHash;

    /** 状态（CANDIDATE/ACTIVE/RETIRED） */
    private String status;

    /** 乐观锁版本 */
    private Integer version;
}
