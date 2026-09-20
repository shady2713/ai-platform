package com.basicframework.module.ai.controller.admin.connector.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 连接器（协议层 VO）：**不含秘密与密文**，只回"是否已配置"。 */
@Schema(description = "管理后台 - AI 连接器")
@Data
@Accessors(chain = true)
public class AiConnectorRespVO {

    @Schema(description = "连接器编号")
    private Long id;

    @Schema(description = "连接器标识")
    private String code;

    @Schema(description = "连接器名称")
    private String name;

    @Schema(description = "类型（HTTP/MYSQL）")
    private String connectorType;

    @Schema(description = "状态（ENABLED/DISABLED）")
    private String status;

    @Schema(description = "声明式配置（结构化字段，不含秘密）")
    private String configJson;

    @Schema(description = "是否已配置秘密")
    @ToString.Exclude
    private Boolean credentialConfigured;

    @Schema(description = "秘密版本（0 表示未配置）")
    @ToString.Exclude
    private Integer credentialRevision;

    @Schema(description = "是否被数据集/工具引用（引用后不可删除）")
    private Boolean referenced;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
