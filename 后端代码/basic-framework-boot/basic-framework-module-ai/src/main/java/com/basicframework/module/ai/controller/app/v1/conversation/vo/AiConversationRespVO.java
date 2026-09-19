package com.basicframework.module.ai.controller.app.v1.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 会话（应用端协议层 VO）：只暴露归属标识与固定版本，不回显业务上下文正文。 */
@Schema(description = "应用端 - AI 会话")
@Data
@Accessors(chain = true)
@ToString(exclude = {"businessContext"})
public class AiConversationRespVO {

    @Schema(description = "会话编号")
    private Long id;

    @Schema(description = "会话业务键")
    private String conversationKey;

    @Schema(description = "会话标题")
    private String title;

    @Schema(description = "绑定的服务编号")
    private Long serviceId;

    @Schema(description = "固定的发布版本编号（首个运行解析后写入）")
    private Long releaseId;

    @Schema(description = "业务上下文（已注册字段的 JSON 对象文本）")
    private String businessContext;

    @Schema(description = "消息条数")
    private Integer messageCount;

    @Schema(description = "最后一条消息时间")
    private LocalDateTime lastMessageTime;

    @Schema(description = "状态（ACTIVE/DELETED）")
    private String status;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
