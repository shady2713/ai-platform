package com.basicframework.module.ai.service.conversation;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationDO;
import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.service.conversation.dto.AiConversationCreateDTO;
import com.basicframework.module.ai.service.conversation.dto.AiConversationMessageSaveDTO;
import com.basicframework.module.ai.service.conversation.dto.AiConversationRunContextDTO;
import java.util.List;

/**
 * 会话与消息存储（O01）。
 *
 * <p>约束：
 * <ul>
 *   <li><b>归属由服务端身份决定</b>：所有读写都按当前会话解析出的应用+主体过滤，
 *       越权访问与不存在同语义（404），客户端不能自报归属；</li>
 *   <li><b>分页稳定</b>：会话按编号倒序分页，消息按会话内递增序号推进，并发写入不会重复或跳过；</li>
 *   <li><b>删除先关闭访问</b>：删除把会话置 DELETED 并关闭其消息访问，读取立即 404；
 *       正文的物理清理与保留期由 O06 的清理任务按批次处理；</li>
 *   <li><b>旧上下文不得绕过当前授权</b>：{@link #loadRunContext} 在返回历史与固定版本之前
 *       按**当前**授权重新判定固定版本（S03 的固定值解析），失权即拒绝。</li>
 * </ul>
 */
public interface AiConversationService {

    /** 创建会话（业务键在同一应用+主体内唯一）。 */
    Long create(AiConversationCreateDTO createDTO);

    /** 当前主体的会话分页（按编号倒序）。 */
    PageResult<AiConversationDO> getPage(PageParam pageParam);

    /** 读取会话（非本人或不存在的会话同语义拒绝）。 */
    AiConversationDO getConversation(Long id);

    /** 重命名会话（乐观锁）。 */
    void rename(Long id, String title, Integer version);

    /** 删除会话：先关闭访问（状态与消息），再由保留策略清理正文。 */
    void delete(Long id, Integer version);

    /** 绑定服务（未固定发布版本时允许改绑；已固定后需显式新建或迁移会话）。 */
    void bindService(Long id, Long serviceId, Integer version);

    /** 固定发布版本：首个 run 解析到 releaseId 后写入，后续消息沿用该版本。 */
    void bindRelease(Long id, Long releaseId, Integer version);

    /** 追加消息（角色与长度校验；序号在会话内递增）。 */
    Long appendMessage(AiConversationMessageSaveDTO saveDTO);

    /** 会话消息（按序号升序，afterSequence 之后最多 limit 条）。 */
    List<AiConversationMessageDO> listMessages(Long conversationId, Integer afterSequence, Integer limit);

    /**
     * 运行上下文：重新鉴权固定版本后返回会话、固定值、业务上下文与最近历史。
     *
     * <p>这是"旧上下文无权限不得进入新 run"的落点：会话历史本身不携带权限，
     * 每次进入新运行都必须按当前授权重新判定。
     */
    AiConversationRunContextDTO loadRunContext(Long conversationId, Integer historyLimit);
}
