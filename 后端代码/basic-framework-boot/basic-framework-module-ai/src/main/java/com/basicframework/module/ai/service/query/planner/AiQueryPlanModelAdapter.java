package com.basicframework.module.ai.service.query.planner;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_MODEL_OUTPUT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.StructuredModelRequest;
import com.basicframework.framework.ai.core.model.StructuredModelResult;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.domain.runtime.AiModelFailureCodes;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.AiModelInvocationResult;
import com.basicframework.module.ai.service.model.AiModelInvocationService;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 查询计划模型适配器（D05）：把规划器的结构化请求交给 M05 的统一调用编排。
 *
 * <p>与 S04 调试链路一致：策略校验先于网络调用、上游失败按稳定原因抛出、
 * 不回传上游报文与提示词；这里额外把"模型没给出可用 JSON 对象"收敛为
 * {@code AI_QUERY_MODEL_OUTPUT_INVALID}，让规划器只面对两种结果：可用输出或稳定错误。
 */
@Component
@RequiredArgsConstructor
public class AiQueryPlanModelAdapter implements AiQueryPlanModel {

    /** 规划调用的默认超时（毫秒）。 */
    private static final long TIMEOUT_MILLIS = 20_000L;

    private final AiModelEndpointService endpointService;

    private final AiModelInvocationService invocationService;

    @Override
    public String propose(Long endpointId, String prompt, String jsonSchema) {
        if (endpointId == null) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        String modelId = currentModelId(endpointId);
        try {
            AiModelInvocationResult<StructuredModelResult> invoked = invocationService.generateStructured(
                    endpointId,
                    new StructuredModelRequest(modelId, prompt, jsonSchema, Duration.ofMillis(TIMEOUT_MILLIS)),
                    // 数据集摘要属内部控制面元数据（字段名/单位/枚举），按内部等级外发
                    AiOutboundLevel.L2_INTERNAL);
            StructuredModelResult output = invoked.output();
            if (output == null || output.json() == null || output.json().isBlank()) {
                throw exception(AI_QUERY_MODEL_OUTPUT_INVALID);
            }
            return output.json();
        } catch (ModelException failure) {
            throw AiModelFailureCodes.toServiceException(failure);
        }
    }

    /** 当前生效的模型标识（与调试链路同一取法：修订列表首条即当前版本）。 */
    private String currentModelId(Long endpointId) {
        List<AiModelEndpointRevisionDO> revisions = endpointService.getRevisions(endpointId);
        if (revisions.isEmpty()) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return revisions.get(0).getModelId();
    }
}
