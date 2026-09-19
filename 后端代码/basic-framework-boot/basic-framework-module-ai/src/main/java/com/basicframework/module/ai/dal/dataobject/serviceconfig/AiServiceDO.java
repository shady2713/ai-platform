package com.basicframework.module.ai.dal.dataobject.serviceconfig;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 服务草稿（S01）：模型端点、提示词、输入/输出 Schema、所需能力与运行主体类型。
 *
 * <p>草稿可反复编辑（乐观锁 + draftRevision 递增）；发布版本见 {@link AiServiceReleaseDO}。
 */
@TableName("ai_service")
@KeySequence("ai_service_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiServiceDO extends SoftDeletableDO {

    /** 状态：草稿 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：就绪（可发布） */
    public static final String STATUS_READY = "READY";

    /** 状态：已归档 */
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    /** 服务编号 */
    @TableId
    private Long id;

    /** 所属应用编号（服务归属与绑定越权校验的基准） */
    private Long appId;

    /** 服务标识（应用内唯一） */
    private String code;

    /** 服务名称 */
    private String name;

    /** 服务说明 */
    private String description;

    /** 状态（DRAFT/READY/ARCHIVED） */
    private String status;

    /** 模型端点编号 */
    private Long modelEndpointId;

    /** 提示词模板 */
    private String promptTemplate;

    /** 输入 JSON Schema */
    private String inputSchema;

    /** 输出 JSON Schema（结构化输出时必填） */
    private String outputSchema;

    /** 所需能力（逗号分隔） */
    private String requiredCapabilities;

    /** 运行主体类型（APP/USER） */
    private String runSubjectType;

    /** 发布要求的评测得分门槛（0-100，0 表示只要求"评测通过"） */
    private Integer evalThreshold;

    /** 草稿修订号：配置变更递增 */
    private Integer draftRevision;

    /** 乐观锁版本 */
    private Integer version;
}
