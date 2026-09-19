package com.basicframework.module.ai.service.serviceconfig.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 服务草稿新增/修改（服务层 DTO）：不依赖协议层 VO。 */
@Data
@Accessors(chain = true)
public class AiServiceSaveDTO {

    /** 服务编号（修改时必填） */
    private Long id;

    /** 所属应用编号 */
    private Long appId;

    /** 服务标识（应用内唯一；创建后不可修改） */
    private String code;

    /** 服务名称 */
    private String name;

    /** 服务说明 */
    private String description;

    /** 模型端点编号 */
    private Long modelEndpointId;

    /** 提示词模板 */
    private String promptTemplate;

    /** 输入 JSON Schema（JSON 对象文本） */
    private String inputSchema;

    /** 输出 JSON Schema（结构化输出时必填） */
    private String outputSchema;

    /** 所需能力（白名单词汇） */
    private List<String> requiredCapabilities;

    /** 运行主体类型（APP/USER） */
    private String runSubjectType;

    /** 发布要求的评测得分门槛（0-100，缺省 0） */
    private Integer evalThreshold;

    /** 乐观锁版本（修改时必填） */
    private Integer version;
}
