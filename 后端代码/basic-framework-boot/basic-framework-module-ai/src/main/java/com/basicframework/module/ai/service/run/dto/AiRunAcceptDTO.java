package com.basicframework.module.ai.service.run.dto;

import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 运行受理请求（O02）：归属来自服务端身份；消息与上下文正文不进日志。 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"message", "businessContext", "attachmentKeys"})
public class AiRunAcceptDTO {

    /** 服务编号 */
    private Long serviceId;

    /** 会话编号（可空：无会话的一次性运行） */
    private Long conversationId;

    /** 幂等键（调用方提供，同一主体内唯一；16-128 位） */
    private String idempotencyKey;

    /** 用户消息 */
    private String message;

    /** 附件标识（业务侧文件标识；顺序无关） */
    private List<String> attachmentKeys;

    /** 业务上下文（已注册字段的 JSON 对象文本） */
    private String businessContext;

    /** 本次输入的数据分级（L1–L4；端点外发上限由平台策略强制） */
    private String dataLevel;
}
