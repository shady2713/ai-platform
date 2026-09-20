package com.basicframework.module.ai.controller.admin.connector.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 连接器新增/修改（协议层 VO）：秘密只提交不回显。 */
@Schema(description = "管理后台 - AI 连接器新增/修改")
@Data
@Accessors(chain = true)
@ToString(exclude = {"credential"})
public class AiConnectorSaveReqVO {

    @Schema(description = "连接器编号（修改时必填）")
    private Long id;

    @Schema(
            description = "连接器标识（字母开头，3-64 位字母数字/连字符/下划线；创建后不可修改）",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "crm-http")
    @NotBlank
    @Size(max = 64)
    private String code;

    @Schema(description = "连接器名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String name;

    @Schema(description = "类型（HTTP/MYSQL）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String connectorType;

    @Schema(
            description = "声明式配置（JSON 对象文本；HTTP：baseUrl/method/healthPath/authType/timeoutMillis；"
                    + "MYSQL：host/port/database/username/sslMode）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 2000)
    private String configJson;

    @Schema(description = "秘密（HTTP Bearer/Basic 凭据或 MySQL 密码；修改时留空表示保留）")
    @Size(max = 1024)
    private String credential;

    @Schema(description = "乐观锁版本（修改时必填）")
    @PositiveOrZero
    private Integer version;

    @Schema(description = "轮换秘密时的乐观锁版本（轮换接口使用）")
    @Positive
    private Integer rotateVersion;
}
