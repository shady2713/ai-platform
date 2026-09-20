package com.basicframework.module.ai.controller.admin.connector.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 连接器操作（协议层 VO）：声明式字段，不含脚本与外部引用。 */
@Schema(description = "管理后台 - 连接器操作")
@Data
@Accessors(chain = true)
public class AiConnectorOperationRespVO {

    @Schema(description = "操作编号")
    private Long id;

    @Schema(description = "连接器编号")
    private Long connectorId;

    @Schema(description = "操作标识")
    private String operationKey;

    @Schema(description = "HTTP 方法（GET/POST）")
    private String httpMethod;

    @Schema(description = "路径模板")
    private String pathTemplate;

    @Schema(description = "操作说明")
    private String summary;

    @Schema(description = "参数声明")
    private String parameterJson;

    @Schema(description = "响应提取规则")
    private String responseJson;

    @Schema(description = "分页规则（页数上限、游标字段）")
    private String paginationJson;

    @Schema(description = "状态（DRAFT/PUBLISHED）")
    private String status;

    @Schema(description = "乐观锁版本")
    private Integer version;
}
