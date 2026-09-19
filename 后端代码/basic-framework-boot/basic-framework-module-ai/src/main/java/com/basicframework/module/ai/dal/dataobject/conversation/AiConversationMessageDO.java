package com.basicframework.module.ai.dal.dataobject.conversation;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 会话消息（O01）：受控业务数据（L3），按主体过滤读取，正文不进日志。
 *
 * <p>{@code sequence_no} 在会话内从 1 递增并带唯一约束：分页按序号推进，
 * 并发写入不会让分页重复或跳过。{@code content_hash} 是正文摘要，用于审计与去重。
 */
@TableName("ai_conversation_message")
@KeySequence("ai_conversation_message_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiConversationMessageDO extends SoftDeletableDO {

    /** 状态：可用 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已删除 */
    public static final String STATUS_DELETED = "DELETED";

    /** 角色：用户 */
    public static final String ROLE_USER = "user";

    /** 角色：助手 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** 角色：系统 */
    public static final String ROLE_SYSTEM = "system";

    /** 消息编号 */
    @TableId
    private Long id;

    /** 会话编号 */
    private Long conversationId;

    /** 应用编号（冗余自会话，用于主体过滤） */
    private Long applicationId;

    /** 主体类型（冗余自会话，用于主体过滤） */
    private String subjectType;

    /** 外部用户标识（冗余自会话，用于主体过滤） */
    private String externalUserId;

    /** 会话内序号（从 1 递增） */
    private Integer sequenceNo;

    /** 角色（user/assistant/system） */
    private String role;

    /** 消息正文（受控业务数据；不参与 toString，避免进入日志） */
    @ToString.Exclude
    private String content;

    /** 正文 SHA-256（摘要不等于正文） */
    private String contentHash;

    /** 产生该消息的运行编号（O02 起写入） */
    private Long sourceRunId;

    /** 状态（ACTIVE/DELETED） */
    private String status;

    /** 乐观锁版本 */
    private Integer version;
}
