package com.basicframework.module.ai.service.run;

import com.basicframework.module.ai.dal.dataobject.conversation.AiConversationMessageDO;
import com.basicframework.module.ai.dal.dataobject.run.AiRunDO;
import com.basicframework.module.ai.dal.mysql.run.AiRunMapper;
import com.basicframework.module.ai.service.conversation.AiConversationService;
import com.basicframework.module.ai.service.conversation.dto.AiConversationMessageSaveDTO;
import com.basicframework.module.ai.service.task.AiTaskService;
import com.basicframework.module.ai.service.task.dto.AiTaskLeaseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 运行终态写入器（O04）：助手消息与运行终态在**同一事务**内落库，并用两道栅栏防止覆盖。
 *
 * <p>独立成 Bean 的原因：终态写入必须是一个真正的事务边界（同类内自调用不会开启事务）。
 *
 * <p>两道栅栏：
 * <ol>
 *   <li>运行行乐观锁：只有携带当前 version 的写入才生效，重复执行或取消后的写入命中 0 行；</li>
 *   <li>任务租约栅栏：旧 worker（租约已过期、代次已递增）的落库命中 0 行，不会覆盖新 worker 的结果。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiRunTerminalWriter {

    private final AiRunMapper runMapper;

    private final AiConversationService conversationService;

    private final AiTaskService taskService;

    /**
     * 写入终态（可选写入助手消息）。
     *
     * @return true 表示本次写入生效；false 表示运行已被其它路径写入终态，本次不覆盖
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean finish(
            AiTaskLeaseDTO lease, AiRunDO run, String status, String errorCode, int steps, String assistantMessage) {
        int current = run.getVersion() == null ? 0 : run.getVersion();
        if (runMapper.updateWithVersion(
                        new AiRunDO()
                                .setId(run.getId())
                                .setStatus(status)
                                .setStepCount(steps)
                                .setVersion(current + 1),
                        current)
                == 0) {
            // 终态已被写入（重复执行、取消）：不覆盖，也不写入消息
            return false;
        }
        if (StringUtils.hasText(assistantMessage) && run.getConversationId() != null) {
            conversationService.appendMessage(new AiConversationMessageSaveDTO()
                    .setConversationId(run.getConversationId())
                    .setRole(AiConversationMessageDO.ROLE_ASSISTANT)
                    .setContent(assistantMessage)
                    .setSourceRunId(run.getId()));
        }
        if (lease != null) {
            taskService.finish(lease, status, errorCode);
        }
        return true;
    }
}
