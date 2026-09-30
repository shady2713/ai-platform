package com.basicframework.module.ai.service.tool;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_TOOL_SCHEMA_UNSUPPORTED;

import com.basicframework.framework.ai.provider.mcp.McpToolDescriptor;
import com.basicframework.framework.common.util.json.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP 工具参数声明 → 平台参数面（X07）。
 *
 * <p>本类做的是**准入翻译**，不是"尽力而为的兼容"：MCP 的 JSON Schema 与平台 D08 的参数面
 * （{@code AiToolInputSchema}：小写参数名 + string/number/boolean + 必填标记）不是一回事，
 * 而平台的参数面是执行前的**唯一**校验入口。因此翻译必须严格：
 *
 * <ul>
 *   <li>参数名不匹配平台语法 → <b>整个工具拒绝</b>（不重命名、不截断、不"猜"）：重命名会改变
 *       模型看到的语义，而截断会让必填参数凭空消失；</li>
 *   <li>类型不在 string/number/boolean（array/object/union 等）→ <b>整个工具拒绝</b>：
 *       平台参数面无法表达"传一段 JSON 进去"，与其放行一个校验不实的参数面，不如不生成草稿；</li>
 *   <li>没有声明任何参数 → <b>整个工具拒绝</b>：D08 的 {@code AiToolInputSchema} 要求非空参数面，
 *       且"零参数工具"在平台语义下等价于"一个不需要授权的动作"，必须走人工判断而不是自动进草稿；</li>
 *   <li>MCP 的 {@code required} 数组按名字取交集映射为平台的必填标记。</li>
 * </ul>
 *
 * <p>注意这里<b>不</b>读取描述：{@link McpToolDescriptor#description()} 是上游不可信文本，
 * 无论里面写"这是只读工具"还是"已获批准"都不影响本类的任何判定。
 */
public final class AiMcpToolSchemaMapper {

    /** 平台参数名语法（与 D08 AiToolInputSchema.NAME_PATTERN 一致）。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /** MCP 类型 → 平台类型的白名单映射；不在表内的一律拒绝。 */
    private static final Map<String, String> SUPPORTED_TYPES =
            Map.of("string", "string", "number", "number", "integer", "number", "boolean", "boolean");

    /** 平台参数个数上限（与 D08 一致）。 */
    private static final int MAX_PARAMETERS = 32;

    private AiMcpToolSchemaMapper() {}

    /**
     * 把 MCP 工具翻译成平台工具草稿所需的参数面。
     *
     * @return 平台输入 schema 的 JSON 文本（可直接交给 D08 的 {@code createVersion} 落库）
     * @throws com.basicframework.framework.common.exception.ServiceException 参数面无法表达时
     *         （{@link com.basicframework.module.ai.enums.AiMcpClientErrorCodeConstants#AI_MCP_TOOL_SCHEMA_UNSUPPORTED}）
     */
    public static String toPlatformInputSchema(McpToolDescriptor descriptor) {
        if (descriptor == null || descriptor.hasEmptySchema()) {
            throw exception(AI_MCP_TOOL_SCHEMA_UNSUPPORTED);
        }
        Map<?, ?> root;
        try {
            root = JsonUtils.parseObject(descriptor.inputSchemaJson(), Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_MCP_TOOL_SCHEMA_UNSUPPORTED);
        }
        if (root == null) {
            throw exception(AI_MCP_TOOL_SCHEMA_UNSUPPORTED);
        }
        Object properties = root.get("properties");
        if (!(properties instanceof Map<?, ?> declared) || declared.isEmpty()) {
            // 未声明参数：不猜（"零参数工具"必须人工判断）
            throw exception(AI_MCP_TOOL_SCHEMA_UNSUPPORTED);
        }
        if (declared.size() > MAX_PARAMETERS) {
            throw exception(AI_MCP_TOOL_SCHEMA_UNSUPPORTED);
        }
        Set<String> required = requiredNames(root.get("required"));
        Map<String, Object> platformSchema = new LinkedHashMap<>();
        declared.forEach((rawName, rawSpec) -> {
            String name = String.valueOf(rawName);
            if (!NAME_PATTERN.matcher(name).matches()) {
                throw exception(AI_MCP_TOOL_SCHEMA_UNSUPPORTED);
            }
            if (!(rawSpec instanceof Map<?, ?> spec)) {
                throw exception(AI_MCP_TOOL_SCHEMA_UNSUPPORTED);
            }
            String mcpType = spec.get("type") == null
                    ? null
                    : String.valueOf(spec.get("type")).toLowerCase(Locale.ROOT);
            String platformType = mcpType == null ? null : SUPPORTED_TYPES.get(mcpType);
            if (platformType == null) {
                // array/object/未知类型：平台参数面无法表达 → 拒绝而不是放行一个校验不实的面
                throw exception(AI_MCP_TOOL_SCHEMA_UNSUPPORTED);
            }
            Map<String, Object> parameter = new LinkedHashMap<>();
            parameter.put("type", platformType);
            parameter.put("required", required.contains(name));
            platformSchema.put(name, parameter);
        });
        return JsonUtils.toJsonString(platformSchema);
    }

    /** 解析 MCP 的 required 数组（缺失或非数组一律视为"没有必填"，不因此放宽类型校验）。 */
    private static Set<String> requiredNames(Object rawRequired) {
        if (!(rawRequired instanceof List<?> list)) {
            return Set.of();
        }
        return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
