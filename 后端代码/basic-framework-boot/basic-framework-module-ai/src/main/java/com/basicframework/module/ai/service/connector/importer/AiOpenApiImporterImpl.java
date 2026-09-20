package com.basicframework.module.ai.service.connector.importer;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_IMPORT_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.service.connector.importer.dto.AiConnectorOperationDraftDTO;
import com.basicframework.module.ai.service.connector.importer.dto.AiOpenApiImportResultDTO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * OpenAPI 导入器实现（D02）。
 *
 * <p>只做**声明式翻译**：文档里的操作被翻译成"方法 + 路径模板 + 参数声明 + 响应/分页规则"，
 * 不执行、不抓取、不注册任何回调。任何超出限定范围的内容（外部 $ref、非 GET/POST、header 参数、
 * 超大文档）都会被跳过并记录原因，保证"导入"这一步不会变成隐式的网络访问或脚本执行。
 */
@Service
public class AiOpenApiImporterImpl implements AiOpenApiImporter {

    /** 文档大小上限（512KB）。 */
    private static final int MAX_DOCUMENT_LENGTH = 512 * 1024;

    /** 单次导入的操作数量上限。 */
    private static final int MAX_OPERATIONS = 200;

    /** 单个操作的参数数量上限。 */
    private static final int MAX_PARAMETERS = 32;

    /** 允许的方法。 */
    private static final Set<String> METHODS = Set.of("get", "post");

    /** 允许的参数位置（header 明确不在其中）。 */
    private static final Set<String> ALLOWED_LOCATIONS = Set.of("query", "path", "body");

    /** 允许的参数类型（JSON Schema 的基础类型）。 */
    private static final Set<String> ALLOWED_TYPES = Set.of("string", "integer", "number", "boolean", "array");

    /** 路径模板：以 / 开头，允许字母数字与 - _ . / 以及 {param} 占位符。 */
    private static final Pattern PATH_PATTERN = Pattern.compile("^(/[A-Za-z0-9_./-]*|/\\{[A-Za-z0-9_]{1,64}})+$");

    /** 操作标识：字母数字与 - _ . : 开头非数字。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.:-]{0,127}$");

    @Override
    public AiOpenApiImportResultDTO importDocument(String documentJson) {
        if (!StringUtils.hasText(documentJson) || documentJson.length() > MAX_DOCUMENT_LENGTH) {
            throw exception(AI_CONNECTOR_IMPORT_INVALID);
        }
        Map<?, ?> document;
        try {
            document = JsonUtils.parseObject(documentJson, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_CONNECTOR_IMPORT_INVALID);
        }
        if (document == null || !(document.get("paths") instanceof Map<?, ?> paths)) {
            throw exception(AI_CONNECTOR_IMPORT_INVALID);
        }
        List<AiConnectorOperationDraftDTO> operations = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<?, ?> pathEntry : paths.entrySet()) {
            String path = String.valueOf(pathEntry.getKey());
            if (!(pathEntry.getValue() instanceof Map<?, ?> operationsByMethod)) {
                continue;
            }
            if (!PATH_PATTERN.matcher(path).matches() || path.contains("..")) {
                // 目录穿越（".."）与非法字符一律拒绝：路径模板只能由源侧显式声明
                skipped.add(path + "：路径模板不合法");
                continue;
            }
            for (Map.Entry<?, ?> methodEntry : operationsByMethod.entrySet()) {
                String method = String.valueOf(methodEntry.getKey()).toLowerCase(java.util.Locale.ROOT);
                if (!METHODS.contains(method)) {
                    // 非 GET/POST（含 options/head/put/delete/patch）一律跳过
                    skipped.add(method.toUpperCase(java.util.Locale.ROOT) + " " + path + "：只支持 GET/POST");
                    continue;
                }
                if (!(methodEntry.getValue() instanceof Map<?, ?> operation)) {
                    continue;
                }
                if (operations.size() >= MAX_OPERATIONS) {
                    skipped.add(method.toUpperCase(java.util.Locale.ROOT) + " " + path + "：超出单次导入上限");
                    continue;
                }
                draft(method, path, operation, operations, skipped);
            }
        }
        if (operations.isEmpty()) {
            // 没有任何可导入的操作：报错而不是"导入 0 条"（避免把不可用文档当成成功）
            throw exception(AI_CONNECTOR_IMPORT_INVALID);
        }
        return new AiOpenApiImportResultDTO().setOperations(operations).setSkipped(skipped);
    }

    private void draft(
            String method,
            String path,
            Map<?, ?> operation,
            List<AiConnectorOperationDraftDTO> operations,
            List<String> skipped) {
        String label = method.toUpperCase(java.util.Locale.ROOT) + " " + path;
        String operationId = operation.get("operationId") == null ? null : String.valueOf(operation.get("operationId"));
        String operationKey = StringUtils.hasText(operationId)
                ? operationId.trim()
                : method + path.replace('/', '_').replace("{", "").replace("}", "");
        if (!KEY_PATTERN.matcher(operationKey).matches()) {
            skipped.add(label + "：操作标识不合法");
            return;
        }
        Map<String, Map<String, Object>> parameters = new LinkedHashMap<>();
        if (operation.get("parameters") instanceof List<?> declared) {
            for (Object item : declared) {
                if (!(item instanceof Map<?, ?> parameter)) {
                    continue;
                }
                if (externalRef(parameter)) {
                    // 外部引用不抓取：跳过该操作（而不是"尽力解析"）
                    skipped.add(label + "：包含外部 $ref，需先在源侧展开");
                    return;
                }
                String location = parameter.get("in") == null ? "" : String.valueOf(parameter.get("in"));
                String name = parameter.get("name") == null ? "" : String.valueOf(parameter.get("name"));
                if ("header".equalsIgnoreCase(location) || "cookie".equalsIgnoreCase(location)) {
                    // 请求头只能来自连接器自身配置：导入时显式丢弃并记录
                    skipped.add(label + "：忽略 " + location + " 参数 " + name);
                    continue;
                }
                if (!ALLOWED_LOCATIONS.contains(location)
                        || !KEY_PATTERN.matcher(name).matches()) {
                    skipped.add(label + "：参数声明不合法（" + name + "）");
                    continue;
                }
                if (parameters.size() >= MAX_PARAMETERS) {
                    skipped.add(label + "：参数数量超出上限");
                    break;
                }
                parameters.put(name, parameter(location, parameter));
            }
        }
        if (operation.get("requestBody") instanceof Map<?, ?> requestBody) {
            if (externalRef(requestBody)) {
                skipped.add(label + "：请求体包含外部 $ref，需先在源侧展开");
                return;
            }
            if (method.equals("post")) {
                bodyParameters(requestBody, parameters, label, skipped);
            }
        }
        operations.add(new AiConnectorOperationDraftDTO()
                .setOperationKey(operationKey)
                .setHttpMethod(method.toUpperCase(java.util.Locale.ROOT))
                .setPathTemplate(path)
                .setSummary(operation.get("summary") == null ? "" : String.valueOf(operation.get("summary")))
                .setParameters(parameters)
                .setResponse(defaultResponse())
                .setPagination(defaultPagination()));
    }

    private void bodyParameters(
            Map<?, ?> requestBody, Map<String, Map<String, Object>> parameters, String label, List<String> skipped) {
        Object content = requestBody.get("content");
        if (!(content instanceof Map<?, ?> contentByType)
                || !(contentByType.get("application/json") instanceof Map<?, ?> jsonContent)) {
            skipped.add(label + "：请求体不是 application/json，忽略");
            return;
        }
        Object schema = jsonContent.get("schema");
        if (!(schema instanceof Map<?, ?> schemaMap)) {
            return;
        }
        if (externalRef(schemaMap)) {
            skipped.add(label + "：请求体 schema 包含外部 $ref，忽略");
            return;
        }
        if (schemaMap.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (!KEY_PATTERN.matcher(name).matches() || parameters.size() >= MAX_PARAMETERS) {
                    continue;
                }
                Map<String, Object> declared = new LinkedHashMap<>();
                declared.put("in", "body");
                declared.put(
                        "required", schemaMap.get("required") instanceof List<?> required && required.contains(name));
                declared.put("type", typeOf(entry.getValue()));
                parameters.put(name, declared);
            }
        }
    }

    private static Map<String, Object> parameter(String location, Map<?, ?> parameter) {
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("in", location);
        declared.put("required", Boolean.TRUE.equals(parameter.get("required")) || "path".equals(location));
        Object schema = parameter.get("schema");
        declared.put("type", typeOf(schema));
        return declared;
    }

    /** 类型白名单：未知类型收敛为 string（不做任何脚本或格式声明）。 */
    private static String typeOf(Object schema) {
        if (schema instanceof Map<?, ?> schemaMap) {
            Object type = schemaMap.get("type");
            if (type != null && ALLOWED_TYPES.contains(String.valueOf(type))) {
                return String.valueOf(type);
            }
        }
        return "string";
    }

    /** 外部引用：任何非 `#/` 开头的 $ref 都视为外部（不抓取）。 */
    private static boolean externalRef(Map<?, ?> node) {
        Object ref = node.get("$ref");
        return ref != null && !String.valueOf(ref).startsWith("#/");
    }

    /** 默认响应提取：整个响应体作为结果（提取路径由管理员在草稿上确认后再发布）。 */
    private static Map<String, Object> defaultResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rootPath", "");
        response.put("listPath", "");
        return response;
    }

    /** 默认分页：不翻页（分页规则必须显式声明，不做猜测）。 */
    private static Map<String, Object> defaultPagination() {
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("type", "NONE");
        pagination.put("maxPages", 1);
        return pagination;
    }
}
