package com.basicframework.module.ai.service.tool.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 工具版本创建（服务层 DTO）：政策、来源与输入输出 schema 都进版本快照。 */
@Data
@Accessors(chain = true)
public class AiToolVersionSaveDTO {

    /** 工具编号 */
    private Long toolId;

    /** 类型（READ/WRITE；首期只允许发布 READ） */
    private String toolType;

    /** 执行政策（AUTO/CONFIRM/DENY；缺省 DENY） */
    private String policy;

    /** 来源类型（首期只支持 HTTP_OPERATION） */
    private String sourceKind;

    /** 来源标识（operationKey） */
    private String sourceRef;

    /** 输入 schema（JSON：参数名 → {type, required}） */
    private String inputSchemaJson;

    /** 输出 schema（JSON：结果列声明） */
    private String outputSchemaJson;
}
