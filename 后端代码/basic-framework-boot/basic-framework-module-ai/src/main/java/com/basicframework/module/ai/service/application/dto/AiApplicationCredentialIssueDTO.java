package com.basicframework.module.ai.service.application.dto;

import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 凭据签发结果（服务层 DTO）：**唯一**携带秘密明文的载体，只在创建与轮换时返回一次。
 *
 * <p>调用方必须立即把明文交付给对接方，平台不再有任何途径恢复它（库里只有摘要）。
 */
@Data
@Accessors(chain = true)
public class AiApplicationCredentialIssueDTO {

    /** 应用（响应不含任何秘密字段） */
    private AiApplicationDO application;

    /** 一次性明文秘密；服务端不保存、日志不记录、后续接口不回显 */
    @ToString.Exclude
    private String secret;

    /** 凭据编号（便于审计与吊销定位；字段名含 credential，按敏感字段目录排除出 toString） */
    @ToString.Exclude
    private Long credentialId;
}
