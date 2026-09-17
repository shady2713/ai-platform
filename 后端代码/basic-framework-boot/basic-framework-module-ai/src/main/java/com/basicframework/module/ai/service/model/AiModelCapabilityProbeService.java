package com.basicframework.module.ai.service.model;

import com.basicframework.module.ai.service.model.dto.AiModelCapabilityOverviewDTO;
import com.basicframework.module.ai.service.model.dto.AiModelProbeResultDTO;
import java.util.List;

/**
 * 模型能力探测服务（M04）：用**真实调用**确认端点能力，并把结论持久化为可对比的历史。
 *
 * <p>约束：
 * <ul>
 *   <li>探测不受"端点已启用"影响：停用端点也能探测，探测失败也不会被启用状态掩盖；</li>
 *   <li>结论只含稳定码与耗时，不落凭据、提示词或上游报文；</li>
 *   <li>可发布范围 = 端点声明的能力 ∩ 探测确认的能力。</li>
 * </ul>
 */
public interface AiModelCapabilityProbeService {

    /** 对端点的全部探测类型各跑一次并落库，返回本次结论。 */
    List<AiModelProbeResultDTO> probeAll(Long endpointId);

    /** 每种探测类型的最新结论（无历史时为空）。 */
    List<AiModelProbeResultDTO> getLatestResults(Long endpointId);

    /** 能力总览：声明、确认与可发布范围。 */
    AiModelCapabilityOverviewDTO getCapabilityOverview(Long endpointId);
}
