package com.basicframework.module.ai.service.connector.dto;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 连接器新增/修改（服务层 DTO）：秘密只在写入时出现，读取不回显。 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"credential"})
public class AiConnectorSaveDTO {

    /** 连接器编号（修改时必填） */
    private Long id;

    /** 连接器标识（创建后不可修改） */
    private String code;

    /** 连接器名称 */
    private String name;

    /** 类型（HTTP/MYSQL） */
    private String connectorType;

    /** 声明式配置（JSON 对象文本；键与取值见 AiConnectorConfig） */
    private String configJson;

    /** 秘密（HTTP Bearer/Basic 凭据或 MySQL 密码；修改时留空表示保留） */
    private String credential;

    /** 乐观锁版本（修改时必填） */
    private Integer version;
}
