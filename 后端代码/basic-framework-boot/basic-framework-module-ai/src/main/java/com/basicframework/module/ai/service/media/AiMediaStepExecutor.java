package com.basicframework.module.ai.service.media;

import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.service.media.dto.AiMediaStepOutcome;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskLeaseDTO;

/**
 * 媒体执行步骤（X03）：按操作类型执行一次真实上游调用，把产物落成平台私有文件。
 *
 * <p>为什么做成端口而不是写死在 Job 里：图片生成/编辑（X03）与非实时语音（X04）的**任务语义完全一致**
 * （受理、幂等、租约、终态、用量），差别只在"这一次调用怎么发、产物怎么验"。Job 只做编排，
 * 具体能力由实现方接入；没有实现能让该操作成立时，任务以稳定原因码失败，不假装成功。
 *
 * <p>执行器只回报结论（状态/稳定原因码/产物数量/用量），**不写任务终态**：终态由
 * {@link AiMediaTaskService#finish} 带租约栅栏写入，晚到的执行结果不能覆盖已写入的事实。
 */
public interface AiMediaStepExecutor {

    /** 是否承接该操作（取值见 {@link AiMediaTaskDO#OPERATION_GENERATE} 等常量）。 */
    boolean supports(String operation);

    /** 执行一次（异常必须自行收敛为 {@link AiMediaStepOutcome}，不得把上游报文带出来）。 */
    AiMediaStepOutcome execute(AiMediaTaskLeaseDTO lease, AiMediaTaskDO task);
}
