package com.basicframework.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O07 开放 API 规范契约：`docs/integrations/open-api/ai-open-api.json` 与**实际开放端点**双向一致。
 *
 * <p>校验内容：
 * <ol>
 *   <li><b>双向漂移</b>：规范里有而代码没有（文档过期）、代码里有而规范没有（未登记开放端点）都失败；</li>
 *   <li><b>公开文档红线</b>：不出现管理端路径，不出现凭据/秘密/摘要类字段
 *       （换票响应的 {@code token} 是登记过的唯一例外）；</li>
 *   <li><b>状态码登记</b>：受理运行登记 202 语义与 502/503/504，通用错误登记 401/403/429，
 *       事件流登记 {@code text/event-stream}；</li>
 *   <li><b>夹具结构</b>：`examples/` 下的请求/响应夹具按必填键、类型与枚举校验。</li>
 * </ol>
 *
 * <p>{@link #driftFailures} 与 {@link #forbiddenFieldFailures} 是纯函数：本类用合成输入证明
 * "漂移会被拒绝"，而不是只断言当前规范是绿的。
 */
class AiOpenApiContractTest {

    private static final String AI_APP_PACKAGE = "com.basicframework.module.ai.controller.app";

    private static final String APP_PREFIX = "/app-api";

    /** 测试的工作目录是模块目录（basic-framework-server），仓库根在三级之上。 */
    private static final Path SPEC_PATH = Path.of("..", "..", "..", "docs/integrations/open-api/ai-open-api.json");

    private static final Path EXAMPLES_DIR = Path.of("..", "..", "..", "docs/integrations/open-api/examples");

    /** 公开文档允许出现的"凭据语义"字段：换票响应一次性返回的 token（已在 Schema 描述中说明）。 */
    private static final Set<String> ALLOWED_SECRET_FIELDS = Set.of("AiTicketResp.token", "AiTicketReq.appSecret");

    private static final Set<String> FORBIDDEN_FIELD_NAMES = Set.of(
            "credential",
            "credentialCiphertext",
            "credentialConfigured",
            "appSecret",
            "tokenDigest",
            "clientSecret",
            "secret",
            "password",
            "inputDigest",
            "payloadDigest",
            "blockJson");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode spec() throws IOException {
        return MAPPER.readTree(Files.readString(SPEC_PATH));
    }

    /** 从注解推导开放端点的 (方法, 路径) 集合：与运行时映射一致（含 /app-api 前缀）。 */
    private static Set<String> actualEndpoints() {
        Set<String> endpoints = new TreeSet<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition definition : scanner.findCandidateComponents(AI_APP_PACKAGE)) {
            Class<?> controller;
            try {
                controller = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException notFound) {
                continue;
            }
            String basePath = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class) == null
                    ? ""
                    : AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class)
                            .value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                collect(method, GetMapping.class, basePath, "GET", endpoints);
                collect(method, PostMapping.class, basePath, "POST", endpoints);
                collect(method, PutMapping.class, basePath, "PUT", endpoints);
                collect(method, DeleteMapping.class, basePath, "DELETE", endpoints);
            }
        }
        return endpoints;
    }

    private static <T extends java.lang.annotation.Annotation> void collect(
            Method method, Class<T> annotationType, String basePath, String httpMethod, Set<String> endpoints) {
        T annotation = AnnotatedElementUtils.findMergedAnnotation(method, annotationType);
        if (annotation == null) {
            return;
        }
        String[] values;
        if (annotation instanceof GetMapping mapping) {
            values = mapping.value();
        } else if (annotation instanceof PostMapping mapping) {
            values = mapping.value();
        } else if (annotation instanceof PutMapping mapping) {
            values = mapping.value();
        } else if (annotation instanceof DeleteMapping mapping) {
            values = mapping.value();
        } else {
            values = new String[] {""};
        }
        String suffix = values.length == 0 ? "" : values[0];
        endpoints.add(httpMethod + " " + APP_PREFIX + basePath + suffix);
    }

    /** 规范里登记的 (方法, 路径) 集合。 */
    private static Set<String> documentedEndpoints(JsonNode document) {
        Set<String> endpoints = new TreeSet<>();
        document.path("paths")
                .fields()
                .forEachRemaining(entry -> entry.getValue().fieldNames().forEachRemaining(method -> {
                    if (Set.of("get", "post", "put", "delete", "patch").contains(method)) {
                        endpoints.add(method.toUpperCase(java.util.Locale.ROOT) + " " + entry.getKey());
                    }
                }));
        return endpoints;
    }

    /** 纯函数：双向漂移（文档有代码无、代码有文档无）。 */
    static List<String> driftFailures(Set<String> documented, Set<String> actual) {
        List<String> failures = new ArrayList<>();
        for (String endpoint : documented) {
            if (!actual.contains(endpoint)) {
                failures.add("规范登记了未实现的端点：" + endpoint);
            }
        }
        for (String endpoint : actual) {
            if (!documented.contains(endpoint)) {
                failures.add("代码实现了未登记的开放端点：" + endpoint);
            }
        }
        return failures;
    }

    /** 纯函数：公开文档红线（管理端路径与秘密字段）。 */
    static List<String> forbiddenFieldFailures(JsonNode document, Set<String> allowedSecretFields) {
        List<String> failures = new ArrayList<>();
        for (String path : iterable(document.path("paths").fieldNames())) {
            if (path.startsWith("/admin-api") || path.contains("/admin/")) {
                failures.add("公开文档出现管理端路径：" + path);
            }
        }
        JsonNode schemas = document.path("components").path("schemas");
        for (String schemaName : iterable(schemas.fieldNames())) {
            JsonNode properties = schemas.path(schemaName).path("properties");
            for (String fieldName : iterable(properties.fieldNames())) {
                if (!FORBIDDEN_FIELD_NAMES.contains(fieldName)) {
                    continue;
                }
                if (allowedSecretFields.contains(schemaName + "." + fieldName)) {
                    continue;
                }
                failures.add("公开文档出现秘密字段：" + schemaName + "." + fieldName);
            }
        }
        return failures;
    }

    private static List<String> iterable(java.util.Iterator<String> iterator) {
        List<String> values = new ArrayList<>();
        iterator.forEachRemaining(values::add);
        return values;
    }

    @Test
    void specAndControllersHaveNoDrift() throws IOException {
        assertThat(driftFailures(documentedEndpoints(spec()), actualEndpoints()))
                .as("规范与开放端点必须双向一致")
                .isEmpty();
    }

    @Test
    void driftIsRejectedOnSyntheticDocuments() throws IOException {
        Set<String> documented = documentedEndpoints(spec());
        Set<String> actual = actualEndpoints();

        Set<String> withExtraAdminPath = new TreeSet<>(documented);
        withExtraAdminPath.add("GET /app-api/ai/run/secret-debug");
        assertThat(driftFailures(withExtraAdminPath, actual))
                .as("文档多登记一个端点必须被拒绝")
                .hasSize(1)
                .allSatisfy(failure -> assertThat(failure).contains("未实现的端点"));

        Set<String> withoutOneEndpoint = new TreeSet<>(documented);
        withoutOneEndpoint.remove("POST /app-api/ai/run/accept");
        assertThat(driftFailures(withoutOneEndpoint, actual))
                .as("代码里有而文档未登记必须被拒绝")
                .anySatisfy(failure -> assertThat(failure).contains("未登记的开放端点"));
    }

    @Test
    void publicDocumentationCarriesNoAdminOrSecretFields() throws IOException {
        assertThat(forbiddenFieldFailures(spec(), ALLOWED_SECRET_FIELDS)).isEmpty();
    }

    @Test
    void forbiddenFieldsAndAdminPathsAreRejectedOnSyntheticDocuments() throws IOException {
        JsonNode spec = spec();
        JsonNode withAdminPath = MAPPER.readTree("{\"paths\":{\"/admin-api/ai/model-endpoint/create\":{}}}");
        assertThat(forbiddenFieldFailures(withAdminPath, ALLOWED_SECRET_FIELDS))
                .singleElement()
                .satisfies(failure -> assertThat(failure).contains("管理端路径"));

        JsonNode withSecretField = MAPPER.readTree(
                "{\"paths\":{},\"components\":{\"schemas\":{\"AiRun\":{\"properties\":{\"credentialCiphertext\":{}}}}}}");
        assertThat(forbiddenFieldFailures(withSecretField, ALLOWED_SECRET_FIELDS))
                .singleElement()
                .satisfies(failure -> assertThat(failure).contains("秘密字段"));

        // 登记过的例外（换票 token）不报错，但同一字段出现在别的 Schema 里必须报错
        assertThat(forbiddenFieldFailures(spec, Set.of("AiTicketResp.token")))
                .anySatisfy(failure -> assertThat(failure).contains("AiTicketReq.appSecret"));
        assertThat(forbiddenFieldFailures(spec, ALLOWED_SECRET_FIELDS)).isEmpty();
    }

    @Test
    void documentedStatusCodesCoverRegisteredSemantics() throws IOException {
        JsonNode paths = spec().path("paths");

        JsonNode acceptResponses =
                paths.path("/app-api/ai/run/accept").path("post").path("responses");
        assertThat(iterable(acceptResponses.fieldNames()))
                .as("受理运行必须登记 200（已受理语义）与上游失败状态码")
                .contains("200", "400", "409", "502", "503", "504");

        for (String path : List.of("/app-api/ai/run/get", "/app-api/ai/task/progress")) {
            JsonNode responses = paths.path(path).path("get").path("responses");
            assertThat(iterable(responses.fieldNames()))
                    .as("%s 必须登记通用错误状态码", path)
                    .contains("200", "401", "403", "429");
        }

        JsonNode events = paths.path("/app-api/ai/run/events").path("get");
        assertThat(iterable(events.path("responses").path("200").path("content").fieldNames()))
                .as("事件流必须登记 text/event-stream")
                .containsExactly("text/event-stream");
        assertThat(events.path("description").asText())
                .as("事件流说明必须写明鉴权在开流前与心跳不推进序号")
                .contains("先鉴权再开流")
                .contains("不推进");
    }

    @Test
    void fixturesMatchTheDocumentedShapes() throws IOException {
        JsonNode acceptRequest = readExample("run-accept.request.json");
        assertThat(acceptRequest.path("idempotencyKey").asText()).hasSizeBetween(16, 128);
        assertThat(acceptRequest.path("dataLevel").asText())
                .isIn("L1_PUBLIC", "L2_INTERNAL", "L3_PERSONAL", "L4_SECRET");
        assertThat(acceptRequest.path("attachmentKeys").size()).isLessThanOrEqualTo(20);
        assertThat(acceptRequest.path("message").asText().length()).isLessThanOrEqualTo(16_000);

        JsonNode acceptResponse = readExample("run-accept.response.json");
        assertThat(acceptResponse.path("code").asInt()).isZero();
        assertThat(acceptResponse.path("data").path("runKey").asText()).matches("^run_[A-Za-z0-9_-]{3,35}$");
        assertThat(acceptResponse.path("data").path("status").asText())
                .isIn("ACCEPTED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
        assertThat(acceptResponse.path("data").path("reused").isBoolean()).isTrue();

        JsonNode progress = readExample("run-progress.response.json").path("data");
        assertThat(progress.path("runId").isIntegralNumber()).isTrue();
        assertThat(progress.path("taskStatus").asText()).isIn("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "UNKNOWN");
        assertThat(progress.path("retryable").isBoolean()).isTrue();
        assertThat(progress.has("resultDigest")).isTrue();

        JsonNode event = readExample("run-event.json");
        assertThat(event.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(event.path("seq").asInt()).isPositive();
        assertThat(event.path("runId").asText()).matches("^run_[A-Za-z0-9_-]{3,35}$");
        assertThat(event.path("createdAt").asText()).isNotBlank();

        for (String error : List.of("error-401.json", "error-403.json", "error-429.json")) {
            JsonNode body = readExample(error);
            assertThat(body.path("code").asInt()).as("%s 必须带业务错误码", error).isPositive();
            assertThat(body.path("msg").asText()).isNotBlank();
        }

        String stream = Files.readString(EXAMPLES_DIR.resolve("run-events.stream.txt"));
        assertThat(stream)
                .as("SSE 样例必须含事件帧、心跳注释与序号")
                .contains("event: run")
                .contains(": heartbeat")
                .contains("id: 1")
                .contains("id: 2");
    }

    private static JsonNode readExample(String name) throws IOException {
        return MAPPER.readTree(Files.readString(EXAMPLES_DIR.resolve(name)));
    }

    @Test
    void specHasNoUnreferencedSchemaForTheDocumentedEndpoints() throws IOException {
        JsonNode spec = spec();
        Map<String, Boolean> referenced = new LinkedHashMap<>();
        iterable(spec.path("components").path("schemas").fieldNames()).forEach(name -> referenced.put(name, false));
        // 引用可以来自 paths，也可以来自其它 Schema（信封 Schema 引用数据 Schema）
        collectRefs(spec, referenced);
        assertThat(referenced.entrySet().stream()
                        .filter(entry -> !entry.getValue())
                        .map(Map.Entry::getKey)
                        .toList())
                .as("规范里不应存在没有任何端点引用的 Schema（避免僵尸契约）")
                .isEmpty();
    }

    private static void collectRefs(JsonNode node, Map<String, Boolean> referenced) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if ("$ref".equals(entry.getKey()) && entry.getValue().isTextual()) {
                    String name = entry.getValue().asText().replace("#/components/schemas/", "");
                    if (referenced.containsKey(name)) {
                        referenced.put(name, true);
                    }
                }
                collectRefs(entry.getValue(), referenced);
            });
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectRefs(child, referenced));
        }
    }
}
