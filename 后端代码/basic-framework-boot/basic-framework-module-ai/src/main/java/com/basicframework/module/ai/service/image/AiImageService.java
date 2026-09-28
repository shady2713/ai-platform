package com.basicframework.module.ai.service.image;

import com.basicframework.module.ai.service.image.dto.AiImageEditDTO;
import com.basicframework.module.ai.service.image.dto.AiImageGenerateDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;

/**
 * 图片生成与编辑（X03）：参数收窄 → （编辑）底图核验 → 幂等受理持久任务。
 *
 * <p>本接口只负责"把一次请求变成一条可执行的任务事实"；真正的上游调用与产物落库由
 * {@link AiImageStepExecutor} 在后台按租约执行。受理不等上游结果，因此调用方拿到的是任务编号与状态。
 */
public interface AiImageService {

    /** 文生图：受理一条 GENERATE 任务（幂等）。 */
    AiMediaTaskResultDTO generate(AiImageGenerateDTO request);

    /** 底图编辑：先核验底图（A07 + 图片格式/像素），再受理一条 EDIT 任务（幂等）。 */
    AiMediaTaskResultDTO edit(AiImageEditDTO request);
}
