package com.basicframework.module.ai.controller.app.v1.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 会话消息（应用端协议层 VO）：正文只在本次响应中返回，平台不回显摘要以外的审计字段。 */
@Schema(description = "应用端 - AI 会话消息")
@Data
@Accessors(chain = true)
@ToString(exclude = {"content"})
public class AiConversationMessageRespVO {

    @Schema(description = "消息编号")
    private Long id;

    @Schema(description = "会话编号")
    private Long conversationId;

    @Schema(description = "会话内序号（分页按序号推进）")
    private Integer sequenceNo;

    @Schema(description = "角色")
    private String role;

    @Schema(description = "消息正文")
    private String content;

    @Schema(description = "正文摘要（SHA-256）")
    private String contentHash;

    @Schema(description = "产生该消息的运行编号")
    private Long sourceRunId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
