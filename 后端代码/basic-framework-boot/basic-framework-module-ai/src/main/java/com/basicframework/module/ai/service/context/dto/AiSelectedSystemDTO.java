package com.basicframework.module.ai.service.context.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 已选定参与分析的一个系统（Y01）：系统标识 + 该系统的访问指纹。 */
@Data
@Accessors(chain = true)
public class AiSelectedSystemDTO {

    /** 系统（应用）编号 */
    private Long applicationId;

    /** 系统标识（应用 appCode） */
    private String appCode;

    /** 系统名称（仅展示） */
    private String systemName;

    /** 是否为当前会话所在系统 */
    private boolean currentSystem;

    /** 联邦映射编号（当前系统为 null） */
    private Long federationId;

    /** 该系统在选定时刻的访问指纹（身份 + 范围 + 映射版本） */
    private String systemFingerprint;
}
