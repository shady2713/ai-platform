package com.basicframework.module.ai.service.webhook.dto;

import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Webhook 目标新增/修改（服务层 DTO）：协议层 VO 由控制器转换，服务层不依赖 VO。
 *
 * <p>{@link #secret} 是明文签名密钥，只用于加密落库（CredentialCipher），
 * 不参与任何回显、日志与 toString。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"secret"})
public class AiWebhookTargetSaveDTO {

    /** 目标编号（修改时必填） */
    private Long id;

    /** 应用编号（创建时必填；修改时不可改） */
    private Long applicationId;

    /** 目标标识（创建时必填，3-64 位小写字母数字与连字符；创建后不可修改） */
    private String code;

    /** 目标名称 */
    private String name;

    /** 投递地址（http/https） */
    private String targetUrl;

    /** 事件白名单（只接受运行终态三种事件） */
    private List<String> eventTypes;

    /** 签名密钥明文（创建时必填，轮换走独立入口；修改时留空表示保留） */
    private String secret;

    /** 单次投递的最大尝试次数（1-10） */
    private Integer maxAttempts;

    /** 乐观锁版本（修改时必填） */
    private Integer version;
}
