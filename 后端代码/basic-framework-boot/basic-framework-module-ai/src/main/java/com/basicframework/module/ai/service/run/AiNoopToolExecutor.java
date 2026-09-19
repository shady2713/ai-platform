package com.basicframework.module.ai.service.run;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 默认工具执行器（O04）：当前平台**没有**受控工具实现，因此任何工具调用都返回空。
 *
 * <p>它存在的意义是"明确不支持"：模型请求工具调用时，执行器必须按
 * {@code AI_TOOL_UNSUPPORTED} 结束，而不是静默跳过工具继续生成
 * （静默跳过会让调用方以为工具真的执行过）。受控工具实现在 D08/D09 接入，
 * 接入后由具体实现覆盖本 Bean。
 */
@Component
public class AiNoopToolExecutor implements AiToolExecutor {

    @Override
    public Optional<ToolBinding> find(String toolKey) {
        return Optional.empty();
    }
}
