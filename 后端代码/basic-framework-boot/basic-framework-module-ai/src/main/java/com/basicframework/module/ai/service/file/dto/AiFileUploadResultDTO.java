package com.basicframework.module.ai.service.file.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 上传结果（A07）：文件编号 + 业务绑定信息。 */
@Data
@Accessors(chain = true)
public class AiFileUploadResultDTO {

    /** infra 文件编号 */
    private Long fileId;

    /** 业务类型 */
    private String businessType;

    /** 业务对象标识 */
    private String businessKey;

    /** 文件名 */
    private String name;

    /** 大小（字节） */
    private Long size;
}
