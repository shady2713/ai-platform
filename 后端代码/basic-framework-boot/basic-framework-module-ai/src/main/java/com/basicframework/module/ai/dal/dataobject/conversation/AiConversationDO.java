package com.basicframework.module.ai.dal.dataobject.conversation;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 会话（O01）：归属由**服务端会话身份**（应用 + 主体类型 + 外部用户标识）决定。
 *
 * <p>会话绑定服务与固定发布版本：首个 run 解析到 releaseId 后写入，后续消息沿用该版本（S03 的固定值语义）。
 * 业务上下文按宿主传入的已注册字段保存；是否合规在运行时由上下文构造器判定。
 * 删除先关闭访问：{@code status} 置 DELETED 且行逻辑删除，读取立即 404，正文清理由 O06 的保留策略处理。
 */
@TableName("ai_conversation")
@KeySequence("ai_conversation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiConversationDO extends SoftDeletableDO {

    /** 状态：可用 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已删除（访问已关闭，等待保留策略清理正文） */
    public static final String STATUS_DELETED = "DELETED";

    /** 会话编号 */
    @TableId
    private Long id;

    /** 应用编号 */
    private Long applicationId;

    /** 主体类型（APP/USER） */
    private String subjectType;

    /** 可信外部用户标识（APP 主体为空串） */
    private String externalUserId;

    /** 会话业务键（conv_ 前缀，应用+主体内唯一） */
    private String conversationKey;

    /** 会话标题 */
    private String title;

    /** 绑定的 AI 服务编号 */
    private Long serviceId;

    /** 固定的发布版本编号（首个 run 解析后写入） */
    private Long releaseId;

    /** 业务上下文（已注册字段的 JSON 对象文本；不参与权限判定） */
    @ToString.Exclude
    private String businessContext;

    /** 消息条数（逻辑删除后递减） */
    private Integer messageCount;

    /** 最后一条消息时间 */
    private java.time.LocalDateTime lastMessageTime;

    /** 状态（ACTIVE/DELETED） */
    private String status;

    /** 乐观锁版本 */
    private Integer version;
}
