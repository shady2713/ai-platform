package com.basicframework.module.ai.service.tool;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_WRITE_BINDING_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.domain.tool.AiToolInputSchema;
import com.basicframework.module.ai.domain.tool.AiToolPolicy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 写工具绑定（X06）：写工具版本必须**声明**业务幂等键与核对查询，声明随版本快照冻结。
 *
 * <p>为什么声明必须落在版本快照里（而不是执行时由调用方指定）：
 * <ol>
 *   <li><b>幂等键必须是真参数</b>：{@link #idempotencyParam()} 必须是输入 schema 里声明的**必填 string**
 *       参数，因此"写调用带幂等键"是结构上不可省略的——上游据此去重，平台据此保证同一业务意图只有一条动作；</li>
 *   <li><b>核对查询必须是登记过的只读接口</b>：{@link #reconcileOperation()} 指向同连接器已发布的
 *       operation（发布期校验），结果不确定时只能查它，不能由调用方指定任意查询；</li>
 *   <li><b>声明在版本哈希内</b>：政策、schema、来源与写绑定共同构成 {@code schema_hash}，
 *       改绑定的唯一方式是新版本 + 重新发布，因此"确认后悄悄换核对接口"在数据层不可表达。</li>
 * </ol>
 *
 * <p>声明位置：版本 {@code output_schema_json} 里的保留键 {@code write}（输入 schema 只接受参数声明，
 * 无法承载绑定；输出 schema 是版本快照里唯一的自由 JSON 对象，发布前会做严格校验）：
 * <pre>
 * {
 *   "columns": [...],
 *   "write": {"idempotencyParam": "payment_no", "reconcileOperation": "getPayment", "reconcileParam": "payment_no"}
 * }
 * </pre>
 *
 * <p>落库前只保留规范化结果（{@link #canonicalJson()}），未知键一律拒绝：绑定是审核对象，不是备注。
 */
public record AiToolWriteBinding(String idempotencyParam, String reconcileOperation, String reconcileParam) {

    /** 输出 schema 里的保留键。 */
    public static final String WRITE_KEY = "write";

    /** 业务幂等键取值长度上限（与 {@code ai_tool_action.idempotency_key} 列宽一致）。 */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** 参数名模式（与输入 schema 的参数名同一风格）。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private static final Set<String> ALLOWED_KEYS = Set.of("idempotencyParam", "reconcileOperation", "reconcileParam");

    /**
     * 解析并校验绑定（纯函数，发布期与执行期共用）。
     *
     * @param outputSchemaJson 版本输出 schema（JSON 对象）
     * @param toolType 工具类型（READ/WRITE）
     * @param sourceRef 写操作来源 operationKey（核对查询必须与它不同）
     * @param inputSchemaJson 版本输入 schema（幂等键必须是其中声明的必填 string 参数）
     */
    public static AiToolWriteBinding parse(
            String outputSchemaJson, String toolType, String sourceRef, String inputSchemaJson) {
        Map<String, Object> schema = jsonObject(outputSchemaJson);
        Object declared = schema.get(WRITE_KEY);
        AiToolPolicy.ToolType type = AiToolPolicy.ToolType.parse(toolType);
        if (type != AiToolPolicy.ToolType.WRITE) {
            if (declared != null) {
                // 读工具声明写绑定：语义矛盾，直接拒绝（避免"读工具悄悄具备写语义"）
                throw exception(AI_TOOL_WRITE_BINDING_INVALID);
            }
            return null;
        }
        if (!(declared instanceof Map<?, ?> raw)) {
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        raw.forEach((key, value) -> fields.put(String.valueOf(key), value));
        if (!ALLOWED_KEYS.containsAll(fields.keySet())) {
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
        String idempotencyParam = text(fields.get("idempotencyParam"));
        String reconcileOperation = text(fields.get("reconcileOperation"));
        String reconcileParam =
                fields.get("reconcileParam") == null ? idempotencyParam : text(fields.get("reconcileParam"));
        if (!NAME_PATTERN.matcher(idempotencyParam).matches()
                || !NAME_PATTERN.matcher(reconcileParam).matches()
                || !StringUtils.hasText(reconcileOperation)
                || reconcileOperation.length() > 128
                || reconcileOperation.equals(sourceRef)) {
            // 核对查询必须与写操作不同：写操作自己不能当核对证据
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
        AiToolInputSchema inputSchema;
        try {
            inputSchema = AiToolInputSchema.parse(inputSchemaJson);
        } catch (RuntimeException invalid) {
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
        AiToolInputSchema.Parameter parameter = inputSchema.parameter(idempotencyParam);
        if (parameter == null || !"string".equals(parameter.type()) || !parameter.required()) {
            // 幂等键必须是必填文本参数：可选键会让"同一次业务意图"失去身份
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
        return new AiToolWriteBinding(idempotencyParam, reconcileOperation, reconcileParam);
    }

    /** 规范化 JSON（键序固定；用于落库与哈希比对）。 */
    public String canonicalJson() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("idempotencyParam", idempotencyParam);
        canonical.put("reconcileOperation", reconcileOperation);
        canonical.put("reconcileParam", reconcileParam);
        return JsonUtils.toJsonString(canonical);
    }

    /**
     * 从冻结参数里取业务幂等键值（缺失/空白/超长一律拒绝：没有业务键就没有幂等保证）。
     */
    public String idempotencyKeyOf(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get(idempotencyParam);
        String value = raw == null ? null : String.valueOf(raw);
        if (!StringUtils.hasText(value) || value.trim().length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
        return value.trim();
    }

    /** 与动作冻结的绑定是否一致（执行前比对；不一致即要求重新确认）。 */
    public boolean matches(String frozenIdempotencyParam, String frozenReconcileOperation) {
        return idempotencyParam.equals(frozenIdempotencyParam) && reconcileOperation.equals(frozenReconcileOperation);
    }

    /** 声明参数名集合（供发布期校验走到连接器 operation 的声明）。 */
    public Set<String> declaredParameterNames() {
        Set<String> names = new LinkedHashSet<>();
        names.add(idempotencyParam);
        names.add(reconcileParam);
        return names;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, Object> jsonObject(String json) {
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(json, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
        if (parsed == null) {
            throw exception(AI_TOOL_WRITE_BINDING_INVALID);
        }
        Map<String, Object> object = new LinkedHashMap<>();
        parsed.forEach((key, value) -> object.put(String.valueOf(key), value));
        return object;
    }
}
