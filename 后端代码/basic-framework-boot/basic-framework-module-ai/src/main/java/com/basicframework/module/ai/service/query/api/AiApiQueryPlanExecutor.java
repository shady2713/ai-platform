package com.basicframework.module.ai.service.query.api;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_ARGUMENT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OPERATION_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_SCOPE_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.adapter.connector.http.AiHttpConnectorExecutor;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionRequestDTO;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorOperationDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorOperationMapper;
import com.basicframework.module.ai.domain.query.QueryScope;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.result.AiNormalizedResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * API 计划执行器（D07）：把已校验计划绑到**已发布 operation** 上执行并归一化结果。
 *
 * <p>绑定规则（顺序即安全语义）：
 * <ol>
 *   <li>operation 必须存在且**已发布**（草稿不可执行，与 D02 同一语义）；</li>
 *   <li>计划的过滤条件与可信行范围只能映射到 operation **声明过的参数**（未声明即拒绝），
 *       请求头完全不在输入面里——只由连接器配置生成（D02）；</li>
 *   <li>行范围必须能映射到声明参数，否则拒绝执行（不能"没有行约束就看全量"）；</li>
 *   <li>执行结果按条目/耗时/字节预算截断；**任何截断或失败都不得标 COMPLETE**；</li>
 *   <li>归一化只保留计划里的列（投影），金额用十进制，解析失败明确报错。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiApiQueryPlanExecutor {

    private final AiConnectorOperationMapper operationMapper;

    private final AiHttpConnectorExecutor httpConnectorExecutor;

    private final AiApiResultNormalizer normalizer;

    /** 执行计划并返回归一化结果。 */
    public AiNormalizedResult execute(AiApiQueryRequestDTO request) {
        if (request == null
                || request.connectorId() == null
                || !StringUtils.hasText(request.operationKey())
                || request.plan() == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiConnectorOperationDO operation = requirePublishedOperation(request.connectorId(), request.operationKey());
        Set<String> declaredParameters = declaredParameters(operation);

        Map<String, Object> arguments = new LinkedHashMap<>();
        for (ValidatedQueryPlan.Filter filter : request.plan().filters()) {
            String parameter = declaredParameter(declaredParameters, filter.code(), filter.sourceColumn());
            if (parameter == null || filter.values().isEmpty()) {
                // 参数未声明：不接受"猜一个名字发出去"
                throw exception(AI_CONNECTOR_ARGUMENT_INVALID);
            }
            arguments.put(
                    parameter, filter.values().size() == 1 ? filter.values().get(0) : filter.values());
        }
        QueryScope scope = request.scope();
        if (scope == null || !scope.isEffective()) {
            throw exception(AI_QUERY_SCOPE_REQUIRED);
        }
        for (QueryScope.Condition condition : scope.conditions()) {
            String parameter =
                    declaredParameter(declaredParameters, condition.sourceColumn(), condition.sourceColumn());
            if (parameter == null) {
                // 行范围无法施加到上游：拒绝执行，避免"无行约束的全量查询"
                throw exception(AI_QUERY_SCOPE_REQUIRED);
            }
            arguments.put(
                    parameter,
                    condition.values().size() == 1 ? condition.values().get(0) : condition.values());
        }

        long startedAt = System.currentTimeMillis();
        AiConnectorExecutionResultDTO execution = httpConnectorExecutor.execute(new AiConnectorExecutionRequestDTO()
                .setConnectorId(request.connectorId())
                .setOperationKey(request.operationKey())
                .setArguments(arguments));
        long elapsed = System.currentTimeMillis() - startedAt;

        List<String> items = execution.getItems() == null ? List.of() : execution.getItems();
        String status = execution.getStatus();
        String reason = execution.getStoppedReason();
        // 预算截断：条目数、响应字节、总耗时；任何一项触顶都降级为 PARTIAL
        int maxItems = request.effectiveMaxItems();
        if (items.size() > maxItems) {
            items = items.subList(0, maxItems);
            status = AiNormalizedResult.PARTIAL;
            reason = "item-limit";
        }
        int totalBytes = items.stream()
                .mapToInt(item -> item == null ? 0 : item.length())
                .sum();
        if (totalBytes > request.effectiveMaxBytes()) {
            items = truncateByBytes(items, request.effectiveMaxBytes());
            status = AiNormalizedResult.PARTIAL;
            reason = "size-limit";
        }
        if (elapsed > request.effectiveMaxMillis()) {
            status = AiNormalizedResult.PARTIAL;
            reason = "time-limit";
        }
        if (AiNormalizedResult.FAILED.equals(status)) {
            reason = execution.getDetailCode() == null ? "upstream-failed" : execution.getDetailCode();
        }
        return normalizer.normalize(request.plan(), items, status, reason, execution.getPages());
    }

    private AiConnectorOperationDO requirePublishedOperation(Long connectorId, String operationKey) {
        AiConnectorOperationDO operation = operationMapper.selectByKey(connectorId, operationKey);
        if (operation == null) {
            throw exception(AI_CONNECTOR_OPERATION_NOT_FOUND);
        }
        if (!AiConnectorOperationDO.STATUS_PUBLISHED.equals(operation.getStatus())) {
            throw exception(AI_CONNECTOR_OPERATION_NOT_PUBLISHED);
        }
        return operation;
    }

    /** operation 声明的参数名集合（声明是唯一可用的参数面）。 */
    static Set<String> declaredParameters(AiConnectorOperationDO operation) {
        Set<String> names = new LinkedHashSet<>();
        if (!StringUtils.hasText(operation.getParameterJson())) {
            return names;
        }
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(operation.getParameterJson(), Map.class);
        } catch (IllegalArgumentException notAnObject) {
            return names;
        }
        if (parsed == null) {
            return names;
        }
        parsed.keySet().forEach(key -> names.add(String.valueOf(key)));
        return names;
    }

    /** 参数名匹配：先按逻辑码，再按物理列名（两者都必须在声明内）。 */
    private static String declaredParameter(Set<String> declared, String code, String sourceColumn) {
        if (code != null && declared.contains(code)) {
            return code;
        }
        if (sourceColumn != null && declared.contains(sourceColumn)) {
            return sourceColumn;
        }
        return null;
    }

    /** 按字节预算截断条目（保留前缀，顺序不变）。 */
    private static List<String> truncateByBytes(List<String> items, int maxBytes) {
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (String item : items) {
            int length = item == null ? 0 : item.length();
            if (used + length > maxBytes) {
                break;
            }
            kept.add(item);
            used += length;
        }
        return kept;
    }
}
