package com.basicframework.module.ai.adapter.knowledge;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Qdrant REST 适配器（K01）：用 JDK HTTP 客户端直连候选向量服务的 REST 接口。
 *
 * <p>为什么走 REST 而不是 F02 台账里的 Java 客户端：离线构建环境缺少该客户端的传递依赖
 * （{@code com.google.code.gson:gson} 只有 pom、没有 jar），无法在门禁内编译与验证；
 * REST 通道零新增依赖，且天然接入平台的出站治理（基址白名单 + TLS + 超时）。
 * 该偏离与依据记在 {@code docs/integrations/ai-platform-knowledge-index-qdrant.md}。
 *
 * <p>约束：
 * <ul>
 *   <li>基址必须是 https（本地容器验证可显式放开 http，见构造参数）；</li>
 *   <li>API Key 只放在请求头，不进入日志、异常与响应；</li>
 *   <li>写入与检索前校验维度：与集合维度不一致直接拒绝，不依赖服务端行为；</li>
 *   <li>所有失败都收敛为 {@link KnowledgeIndexException} 的稳定原因码，异常消息不含上游报文。</li>
 * </ul>
 */
public class QdrantRestKnowledgeIndexAdapter implements KnowledgeIndexPort {

    /** 载荷里的保留键：保存调用方的逻辑标识（向量服务的点 ID 必须是 UUID 或整数）。 */
    public static final String LOGICAL_ID_KEY = "_logical_id";

    /** 集合信息缓存：维度在集合创建后不可变，避免每次写入都探测。 */
    private final Map<String, Integer> dimensions = new LinkedHashMap<>();

    private final String baseUrl;

    private final String apiKey;

    private final Duration timeout;

    private final HttpClient httpClient;

    /** 生产构造：只接受 https 基址。 */
    public QdrantRestKnowledgeIndexAdapter(String baseUrl, String apiKey, Duration timeout) {
        this(baseUrl, apiKey, timeout, false);
    }

    /**
     * 完整构造。
     *
     * @param allowInsecureHttp 仅用于本地容器验证（生产必须为 false）
     */
    public QdrantRestKnowledgeIndexAdapter(String baseUrl, String apiKey, Duration timeout, boolean allowInsecureHttp) {
        if (baseUrl == null || baseUrl.isBlank() || (!allowInsecureHttp && !baseUrl.startsWith("https://"))) {
            // 生产只允许 https：明文传输向量与载荷是不可接受的
            throw exception(AI_REQUEST_INVALID);
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(10) : timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public CollectionInfo ensureCollection(String collection, int dimension) {
        requireDimension(dimension);
        Optional<CollectionInfo> existing = describeIfPresent(collection);
        if (existing.isPresent()) {
            if (existing.get().dimension() != dimension) {
                // 维度不一致：拒绝而不是"适配"（已有集合的维度不可变）
                throw new KnowledgeIndexException(KnowledgeIndexException.Reason.DIMENSION_MISMATCH, "集合维度与请求维度不一致");
            }
            dimensions.put(collection, dimension);
            return existing.get();
        }
        send("PUT", "/collections/" + collection, Map.of("vectors", Map.of("size", dimension, "distance", "Cosine")));
        dimensions.put(collection, dimension);
        return new CollectionInfo(collection, dimension, 0L);
    }

    @Override
    public void upsert(String collection, List<IndexDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        int dimension = knownDimension(collection);
        List<Map<String, Object>> points = new ArrayList<>();
        for (IndexDocument document : documents) {
            if (document.vector() == null || document.vector().length != dimension) {
                throw new KnowledgeIndexException(KnowledgeIndexException.Reason.DIMENSION_MISMATCH, "写入向量维度与集合不一致");
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", pointId(document.id()));
            point.put("vector", toFloatList(document.vector()));
            Map<String, Object> payload =
                    new LinkedHashMap<>(document.payload() == null ? Map.of() : document.payload());
            // 保留键保存逻辑标识：检索命中时回给调用方的是它自己的 id
            payload.put(LOGICAL_ID_KEY, document.id());
            point.put("payload", payload);
            points.add(point);
        }
        send("PUT", "/collections/" + collection + "/points?wait=true", Map.of("points", points));
    }

    @Override
    public List<SearchHit> search(String collection, float[] vector, int topK, KnowledgeFilter filter) {
        int dimension = knownDimension(collection);
        if (vector == null || vector.length != dimension) {
            throw new KnowledgeIndexException(KnowledgeIndexException.Reason.DIMENSION_MISMATCH, "查询向量维度与集合不一致");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", toFloatList(vector));
        body.put("limit", Math.max(topK, 1));
        body.put("with_payload", true);
        body.put("filter", filterNode(filter));
        Map<String, Object> response = send("POST", "/collections/" + collection + "/points/search", body);
        List<SearchHit> hits = new ArrayList<>();
        for (Object item : asList(response.get("result"))) {
            Map<String, Object> hit = asMap(item);
            Map<String, Object> payload = asMap(hit.get("payload"));
            Object logicalId = payload.get(LOGICAL_ID_KEY);
            hits.add(new SearchHit(
                    logicalId == null ? String.valueOf(hit.get("id")) : String.valueOf(logicalId),
                    ((Number) hit.getOrDefault("score", 0d)).doubleValue(),
                    payload));
        }
        return hits;
    }

    @Override
    public long delete(String collection, KnowledgeFilter filter) {
        // 删除接口不返回条数：先按同一条件计数，再删除（计数用于审计与"删除可验证"）
        long matched = count(collection, filter);
        send("POST", "/collections/" + collection + "/points/delete?wait=true", Map.of("filter", filterNode(filter)));
        return matched;
    }

    /** 按过滤条件统计点数（删除前计数与验证用）。 */
    private long count(String collection, KnowledgeFilter filter) {
        Map<String, Object> response = send(
                "POST",
                "/collections/" + collection + "/points/count",
                Map.of("filter", filterNode(filter), "exact", true));
        Object count = asMap(response.get("result")).get("count");
        return count instanceof Number number ? number.longValue() : 0L;
    }

    @Override
    public void deleteAll(String collection) {
        send(
                "POST",
                "/collections/" + collection + "/points/delete?wait=true",
                Map.of("filter", Map.of("must", List.of())));
    }

    @Override
    public CollectionInfo describe(String collection) {
        return describeIfPresent(collection)
                .orElseThrow(() ->
                        new KnowledgeIndexException(KnowledgeIndexException.Reason.COLLECTION_NOT_FOUND, "集合不存在"));
    }

    private Optional<CollectionInfo> describeIfPresent(String collection) {
        Map<String, Object> response;
        try {
            response = send("GET", "/collections/" + collection, null);
        } catch (KnowledgeIndexException failure) {
            if (failure.getReason() == KnowledgeIndexException.Reason.COLLECTION_NOT_FOUND) {
                return Optional.empty();
            }
            throw failure;
        }
        Map<String, Object> result = asMap(response.get("result"));
        Map<String, Object> vectors =
                asMap(asMap(asMap(result.get("config")).get("params")).get("vectors"));
        int dimension = ((Number) vectors.getOrDefault("size", 0)).intValue();
        long points = ((Number) result.getOrDefault("points_count", 0L)).longValue();
        return Optional.of(new CollectionInfo(collection, dimension, points));
    }

    /** 服务端过滤表达式：白名单字段 + 安全取值，构造为 `must: [{key, match:{any:[...]}}]`。 */
    private static Map<String, Object> filterNode(KnowledgeFilter filter) {
        List<Map<String, Object>> must = new ArrayList<>();
        if (filter != null && !filter.isEmpty()) {
            filter.conditions()
                    .forEach((field, values) ->
                            must.add(Map.of("key", field, "match", Map.of("any", List.copyOf(values)))));
        }
        return Map.of("must", must);
    }

    /**
     * 逻辑标识 → 向量服务点 ID：UUID 与整数原样使用，其余按名字派生**确定性** UUID。
     *
     * <p>确定性映射保证 upsert 幂等（同一逻辑标识永远映射到同一个点），
     * 同时满足向量服务"点 ID 必须是 UUID 或整数"的约束。
     */
    static String pointId(String logicalId) {
        if (logicalId == null || logicalId.isBlank()) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (logicalId.chars().allMatch(Character::isDigit)) {
            return logicalId;
        }
        try {
            return UUID.fromString(logicalId).toString();
        } catch (IllegalArgumentException notUuid) {
            return UUID.nameUUIDFromBytes(logicalId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString();
        }
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private int knownDimension(String collection) {
        Integer cached = dimensions.get(collection);
        if (cached != null) {
            return cached;
        }
        CollectionInfo info = describe(collection);
        dimensions.put(collection, info.dimension());
        return info.dimension();
    }

    private static void requireDimension(int dimension) {
        if (dimension < 1 || dimension > 65_536) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    /** 发送请求并解析响应；失败收敛为稳定原因码，异常消息不含上游报文。 */
    private Map<String, Object> send(String method, String path, Map<String, Object> body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("api-key", apiKey);
        }
        try {
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(JsonUtils.toJsonString(body)));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new KnowledgeIndexException(KnowledgeIndexException.Reason.UNAUTHORIZED, "向量服务拒绝认证");
            }
            if (response.statusCode() == 404) {
                throw new KnowledgeIndexException(KnowledgeIndexException.Reason.COLLECTION_NOT_FOUND, "集合不存在");
            }
            if (response.statusCode() >= 400) {
                throw new KnowledgeIndexException(
                        KnowledgeIndexException.Reason.UPSTREAM_REJECTED, "向量服务返回状态码 " + response.statusCode());
            }
            Map<String, Object> parsed = JsonUtils.parseObject(response.body(), Map.class);
            if (parsed == null) {
                throw new KnowledgeIndexException(KnowledgeIndexException.Reason.INVALID_PAYLOAD, "向量服务响应不是 JSON 对象");
            }
            return parsed;
        } catch (IOException failure) {
            throw new KnowledgeIndexException(KnowledgeIndexException.Reason.TRANSPORT_FAILED, "向量服务不可达", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new KnowledgeIndexException(
                    KnowledgeIndexException.Reason.TRANSPORT_FAILED, "向量服务调用被中断", interrupted);
        }
    }
}
