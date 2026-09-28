package com.basicframework.module.ai.service.tool;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_CHANGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_WRITE_POLICY_UNSUPPORTED;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolDO;
import com.basicframework.module.ai.dal.dataobject.tool.AiToolVersionDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorOperationMapper;
import com.basicframework.module.ai.domain.tool.AiToolPolicy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 写工具准入闸门（X06）：发布期、执行期与核对期的**唯一**写绑定判定点。
 *
 * <p>发布期（{@link #requirePublishable}）要求三件事同时成立，否则写版本不能发布：
 * <ol>
 *   <li>政策不是 AUTO：写调用必须人工确认（模型选择工具不构成授权，FR-25）；</li>
 *   <li>声明合法：业务幂等键是输入 schema 里的必填字符串参数、核对查询与写操作不同
 *       （见 {@link AiToolWriteBinding}）；</li>
 *   <li>绑定**真的能用**：写操作必须声明幂等键参数（否则键发不出去，上游无法去重）、
 *       核对操作必须已发布且声明了核对参数（否则核对查的是无关查询）。</li>
 * </ol>
 *
 * <p>执行期（{@link #requireExecutableBinding}）要求当前已发布版本的绑定与**动作创建时冻结的绑定**
 * 逐项一致：绑定被换过（新版本，或重新导入导致参数声明消失）就必须重新确认，与"确认后改参数"
 * 同一等级的拒绝（409）。核对期（{@link #requireReconcileKeyed}）同样要求登记的查询仍然按业务键过滤。
 */
@Component
@RequiredArgsConstructor
public class AiToolWriteGate {

    private final AiConnectorOperationMapper operationMapper;

    /** 发布期校验：写版本必须带可执行的绑定；读版本不得声明写绑定。返回绑定（读版本为 null）。 */
    public AiToolWriteBinding requirePublishable(AiToolDO tool, AiToolVersionDO version) {
        AiToolWriteBinding binding = AiToolWriteBinding.parse(
                version.getOutputSchemaJson(),
                version.getToolType(),
                version.getSourceRef(),
                version.getInputSchemaJson());
        if (AiToolPolicy.ToolType.parse(version.getToolType()) != AiToolPolicy.ToolType.WRITE) {
            return binding;
        }
        if (AiToolPolicy.parse(version.getPolicy()) == AiToolPolicy.AUTO) {
            // 写调用不允许"自动执行"：先确认再执行是本卡的安全底线
            throw exception(AI_TOOL_WRITE_POLICY_UNSUPPORTED);
        }
        requireOperationDeclares(tool.getConnectorId(), version.getSourceRef(), binding.idempotencyParam());
        requireOperationDeclares(tool.getConnectorId(), binding.reconcileOperation(), binding.reconcileParam());
        return binding;
    }

    /**
     * 执行期校验：当前已发布版本的写绑定必须与动作冻结的绑定一致，且参数声明仍然存在。
     * 只用于写动作（读动作不参与核对）。
     */
    public AiToolWriteBinding requireExecutableBinding(
            AiToolDO tool, AiToolVersionDO published, String frozenIdempotencyParam, String frozenReconcileOperation) {
        AiToolWriteBinding binding = parseQuietly(published);
        if (binding == null || !binding.matches(frozenIdempotencyParam, frozenReconcileOperation)) {
            // 工具类型或声明在确认后被改（例如换成读版本）：旧确认不能继续
            throw exception(AI_TOOL_WRITE_BINDING_CHANGED);
        }
        try {
            requireOperationDeclares(tool.getConnectorId(), published.getSourceRef(), binding.idempotencyParam());
            requireOperationDeclares(tool.getConnectorId(), binding.reconcileOperation(), binding.reconcileParam());
        } catch (ServiceException bindingWithoutSource) {
            // 写操作或核对操作的声明消失了：键发不出去或核对查不准 → 必须重新确认
            throw exception(AI_TOOL_WRITE_BINDING_CHANGED);
        }
        return binding;
    }

    /** 核对期校验：动作冻结的核对查询必须仍存在、已发布，且按业务键参数过滤。 */
    public void requireReconcileKeyed(Long connectorId, String reconcileOperation, String keyParam) {
        AiConnectorOperationDO operation = requireOperation(connectorId, reconcileOperation);
        if (!operationParameterNames(operation).contains(keyParam)) {
            // 查询不再按业务键过滤：程序核对会得出错误结论，必须改人工核对
            throw exception(AI_TOOL_WRITE_BINDING_CHANGED);
        }
    }

    /** 解析绑定但不抛业务异常（读版本或声明缺失时返回 null），供执行期做"绑定变化"检测。 */
    private static AiToolWriteBinding parseQuietly(AiToolVersionDO published) {
        try {
            return AiToolWriteBinding.parse(
                    published.getOutputSchemaJson(),
                    published.getToolType(),
                    published.getSourceRef(),
                    published.getInputSchemaJson());
        } catch (ServiceException notAWriteBinding) {
            return null;
        }
    }

    private void requireOperationDeclares(Long connectorId, String operationKey, String parameterName) {
        AiConnectorOperationDO operation = requireOperation(connectorId, operationKey);
        if (!operationParameterNames(operation).contains(parameterName)) {
            // 键发不出去（或核对查不到键）的绑定等于没有绑定：发布期与执行期都拒绝
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
    }

    private AiConnectorOperationDO requireOperation(Long connectorId, String operationKey) {
        if (connectorId == null || !StringUtils.hasText(operationKey)) {
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
        AiConnectorOperationDO operation = operationMapper.selectByKey(connectorId, operationKey);
        if (operation == null) {
            throw exception(AI_CONNECTOR_OPERATION_NOT_FOUND);
        }
        if (!AiConnectorOperationDO.STATUS_PUBLISHED.equals(operation.getStatus())) {
            throw exception(AI_CONNECTOR_OPERATION_NOT_PUBLISHED);
        }
        return operation;
    }

    /** 从操作的参数声明 JSON 取参数名（值为对象的条目才算声明，与执行侧口径一致）。 */
    private static Set<String> operationParameterNames(AiConnectorOperationDO operation) {
        Set<String> names = new LinkedHashSet<>();
        Map<String, Object> raw = jsonObject(operation.getParameterJson());
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?>) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    private static Map<String, Object> jsonObject(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(json, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            return Map.of();
        }
        if (parsed == null) {
            return Map.of();
        }
        Map<String, Object> object = new LinkedHashMap<>();
        parsed.forEach((key, value) -> object.put(String.valueOf(key), value));
        return object;
    }
}
