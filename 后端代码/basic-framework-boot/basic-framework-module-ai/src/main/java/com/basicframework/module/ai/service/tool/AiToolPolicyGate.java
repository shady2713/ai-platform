package com.basicframework.module.ai.service.tool;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_CONFIRMATION_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_POLICY_DENIED;

import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.dal.mysql.tool.AiToolMapper;
import com.basicframework.module.ai.domain.tool.AiToolPolicy;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工具执行政策闸门（D08）：模型 tool-call 的**唯一**入口判定。
 *
 * <p>判定顺序（顺序即安全语义）：
 * <ol>
 *   <li>工具必须存在且启用（停用与不存在对外同码，避免探测）；</li>
 *   <li>必须绑定到**已发布版本**（草稿不可执行）；</li>
 *   <li>政策必须允许：DENY → 403；CONFIRM → 返回"需确认"（不执行）；AUTO → 继续；</li>
 *   <li>参数按版本里的输入 schema 重新校验（伪造参数/必填缺失/类型不符 → 400）。</li>
 * </ol>
 *
 * <p>调用方（模型）能影响的只有工具标识与参数值：方法、URL、请求头、政策与来源都来自版本快照，
 * 因此"把读工具变成写操作"在结构上不可表达。
 */
@Component
@RequiredArgsConstructor
public class AiToolPolicyGate {

    private final AiToolMapper toolMapper;

    private final AiToolService toolService;

    /** 判定一次工具调用（不执行任何网络/数据库动作）。 */
    public AiToolDecision decide(String toolCode, Map<String, Object> arguments) {
        AiToolVersionDO version = toolService.requirePublishedVersion(toolCode);
        AiToolDO tool = toolMapper.selectById(version.getToolId());
        AiToolPolicy policy = AiToolPolicy.parse(version.getPolicy());
        if (policy == AiToolPolicy.DENY) {
            throw exception(AI_TOOL_POLICY_DENIED);
        }
        Map<String, Object> validated = AiToolServiceImpl.validateArguments(version, arguments);
        if (policy == AiToolPolicy.CONFIRM) {
            // 需要人工确认：判定结果是"待确认"，不带可执行上下文，确认流程由 D09 编排
            throw exception(AI_TOOL_CONFIRMATION_REQUIRED);
        }
        return new AiToolDecision(
                AiToolDecision.Outcome.EXECUTE,
                version,
                tool == null ? null : tool.getConnectorId(),
                version.getSourceRef(),
                validated);
    }

    /** 确认后的执行判定（D09 在用户确认后调用）：政策必须是 CONFIRM，参数重新校验。 */
    public AiToolDecision decideAfterConfirmation(String toolCode, Map<String, Object> arguments) {
        AiToolVersionDO version = toolService.requirePublishedVersion(toolCode);
        if (AiToolPolicy.parse(version.getPolicy()) != AiToolPolicy.CONFIRM) {
            // 非 CONFIRM 政策不允许走"确认后执行"路径（避免确认流程被当成政策绕过手段）
            throw exception(AI_TOOL_POLICY_DENIED);
        }
        AiToolDO tool = toolMapper.selectById(version.getToolId());
        return new AiToolDecision(
                AiToolDecision.Outcome.EXECUTE,
                version,
                tool == null ? null : tool.getConnectorId(),
                version.getSourceRef(),
                AiToolServiceImpl.validateArguments(version, arguments));
    }
}
