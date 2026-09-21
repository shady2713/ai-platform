package com.basicframework.module.ai.domain.tool;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_TOOL_ARGUMENT_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具输入 schema（D08）：声明参数的**唯一**校验入口。
 *
 * <p>为什么工具参数要单独建模而不是复用连接器 operation 的声明：operation 的参数面是"连接器能发什么"，
 * 工具的参数面是"模型被允许填什么"。两者必须分别审核——工具只暴露其中一部分参数，
 * 并且要能拒绝伪造参数（模型可能编造一个没声明的参数名试图改变语义）。
 *
 * <p>规则：白名单参数名、类型（string/number/boolean）、必填标记；未声明参数一律拒绝；
 * 参数名与类型都在校验范围内，取值本身不参与结构。
 */
public final class AiToolInputSchema {

    /** 参数名模式：小写下划线（与工具/字段码同一风格）。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /** 允许的参数类型。 */
    private static final Set<String> TYPES = Set.of("string", "number", "boolean");

    /** 参数个数上限。 */
    public static final int MAX_PARAMETERS = 32;

    /** schema 文本长度上限（列容量 4000）。 */
    public static final int MAX_SCHEMA_LENGTH = 3_500;

    private final Map<String, Parameter> parameters;

    private AiToolInputSchema(Map<String, Parameter> parameters) {
        this.parameters = Map.copyOf(parameters);
    }

    /** 解析并校验输入 schema（JSON 对象：参数名 → {type, required, description}）。 */
    public static AiToolInputSchema parse(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank() || schemaJson.length() > MAX_SCHEMA_LENGTH) {
            throw exception(AI_TOOL_ARGUMENT_INVALID);
        }
        Map<?, ?> raw;
        try {
            raw = JsonUtils.parseObject(schemaJson, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_TOOL_ARGUMENT_INVALID);
        }
        if (raw == null || raw.isEmpty() || raw.size() > MAX_PARAMETERS) {
            throw exception(AI_TOOL_ARGUMENT_INVALID);
        }
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!NAME_PATTERN.matcher(name).matches() || !(value instanceof Map<?, ?> spec)) {
                throw exception(AI_TOOL_ARGUMENT_INVALID);
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            spec.forEach((fieldKey, fieldValue) -> fields.put(String.valueOf(fieldKey), fieldValue));
            for (String fieldKey : fields.keySet()) {
                if (!Set.of("type", "required", "description").contains(fieldKey)) {
                    throw exception(AI_TOOL_ARGUMENT_INVALID);
                }
            }
            String type = fields.get("type") == null
                    ? null
                    : String.valueOf(fields.get("type")).toLowerCase(Locale.ROOT);
            if (type == null || !TYPES.contains(type)) {
                throw exception(AI_TOOL_ARGUMENT_INVALID);
            }
            boolean required = Boolean.TRUE.equals(fields.get("required"));
            parameters.put(name, new Parameter(name, type, required));
        });
        return new AiToolInputSchema(parameters);
    }

    /** 参数名集合（工具允许的完整参数面）。 */
    public Set<String> names() {
        return new LinkedHashSet<>(parameters.keySet());
    }

    /** 必填参数名集合。 */
    public Set<String> requiredNames() {
        Set<String> required = new LinkedHashSet<>();
        parameters.forEach((name, parameter) -> {
            if (parameter.required()) {
                required.add(name);
            }
        });
        return required;
    }

    public Parameter parameter(String name) {
        return name == null ? null : parameters.get(name);
    }

    /**
     * 校验一次工具调用的参数（伪造参数、必填缺失、类型不符一律拒绝）。
     *
     * <p>返回规范化后的参数（只含声明过的键，顺序按 schema 声明顺序），可直接交给执行层。
     */
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        Map<String, Object> supplied = arguments == null ? Map.of() : arguments;
        for (String name : supplied.keySet()) {
            if (!parameters.containsKey(name)) {
                // 伪造/未声明参数：拒绝，不忽略
                throw exception(AI_TOOL_ARGUMENT_INVALID);
            }
        }
        Map<String, Object> validated = new LinkedHashMap<>();
        for (Parameter parameter : parameters.values()) {
            Object value = supplied.get(parameter.name());
            if (value == null) {
                if (parameter.required()) {
                    throw exception(AI_TOOL_ARGUMENT_INVALID);
                }
                continue;
            }
            validated.put(parameter.name(), coerce(value, parameter));
        }
        return validated;
    }

    /** 取值按声明类型归一（number 保持十进制；string 只接受文本；boolean 只接受布尔）。 */
    private static Object coerce(Object value, Parameter parameter) {
        return switch (parameter.type()) {
            case "number" -> {
                if (value instanceof Number number) {
                    yield new BigDecimal(number.toString());
                }
                if (value instanceof String text) {
                    try {
                        yield new BigDecimal(text.trim());
                    } catch (NumberFormatException invalid) {
                        throw exception(AI_TOOL_ARGUMENT_INVALID);
                    }
                }
                throw exception(AI_TOOL_ARGUMENT_INVALID);
            }
            case "boolean" -> {
                if (value instanceof Boolean flag) {
                    yield flag;
                }
                if (value instanceof String text) {
                    String normalized = text.trim().toLowerCase(Locale.ROOT);
                    if ("true".equals(normalized) || "false".equals(normalized)) {
                        yield Boolean.valueOf(normalized);
                    }
                }
                throw exception(AI_TOOL_ARGUMENT_INVALID);
            }
            default -> {
                if (!(value instanceof String text) || text.length() > 1_000) {
                    throw exception(AI_TOOL_ARGUMENT_INVALID);
                }
                yield text;
            }
        };
    }

    /** 规范化 JSON（键序固定：按参数名排序；用于版本哈希与落库）。 */
    public String canonicalJson() {
        List<String> names = parameters.keySet().stream().sorted().toList();
        Map<String, Object> canonical = new LinkedHashMap<>();
        for (String name : names) {
            Parameter parameter = parameters.get(name);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", parameter.type());
            spec.put("required", parameter.required());
            canonical.put(name, spec);
        }
        return JsonUtils.toJsonString(canonical);
    }

    /** 声明的参数（名称 + 类型 + 是否必填）。 */
    public record Parameter(String name, String type, boolean required) {}
}
