package com.basicframework.module.infra.api.file.dto;

import lombok.Data;

/**
 * 文件元数据响应：只暴露跨模块需要的字段，不包含存储路径、配置编号等 infra 内部信息。
 */
@Data
public class FileRespDTO {

    /** 文件编号 */
    private Long id;

    /** 文件名 */
    private String name;

    /** MIME 类型 */
    private String type;

    /** 文件大小，单位字节 */
    private Long size;

    /** 业务类型 */
    private String businessType;

    /** 业务对象编号 */
    private Long businessId;
}
