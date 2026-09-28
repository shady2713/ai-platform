package com.basicframework.server.integration;

import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.framework.ai.core.http.GuardedExternalHttpClient;
import com.basicframework.framework.common.util.json.JsonUtils;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 业务系统模拟器（X06 的"业务模拟器及故障证据"）：一个**有状态**的收款业务系统替身。
 *
 * <p>它与 Mock 的区别：它真的按业务规则处理写请求并保存业务事实——
 * <ul>
 *   <li>{@code POST /payments}：按业务幂等键 {@code payment_no} 落一条收款记录；同一个键重复提交
 *       **不重复入账**（返回 {@code applied=false}），因此"上游允许安全重放"这件事是模拟器自己保证的；</li>
 *   <li>{@code GET /payments?payment_no=...}：按业务键查询（核对查询的实现），返回 {@code items} 列表；</li>
 *   <li>故障注入：超时（入账/未入账两种）、入账后连接中断、入账后 5xx、上游拒绝 4xx——
 *       分别对应"结果未知"与"确定未生效"两类判定；</li>
 *   <li>全部调用事实可核验：收到过哪些参数（幂等键真的发出去了吗）、写过几次、入账次数。</li>
 * </ul>
 *
 * <p>只有模拟器 origin（{@link #SIMULATOR_ORIGIN}）的请求进模拟器；其它目标一律交给**真实的**
 * {@link GuardedExternalHttpClient}（受控出站边界），因此"外发只能走受控出站"在用例里也是真的：
 * 未授权目标会被出站策略拒绝，请求根本不会到达模拟器。
 *
 * <p>作为 {@code @Primary ExternalHttpClient} 注入被测代码，被测代码（连接器执行器）没有任何"测试开关"。
 */
class AiBusinessWriteSimulator implements ExternalHttpClient {

    /** 模拟器 origin（连接器 baseUrl 的合法 https 形态；不解析 DNS，由本客户端内部分派）。 */
    static final String SIMULATOR_ORIGIN = "https://sim.internal";

    /** 故障注入模式。 */
    enum Fault {
        /** 正常：入账并返回 200。 */
        NONE,
        /** 入账后读超时（写已生效但响应丢失 → 结果未定）。 */
        APPLIED_THEN_TIMEOUT,
        /** 未入账即读超时（写未生效但调用方也不知道 → 结果未定）。 */
        TIMEOUT_BEFORE_APPLY,
        /** 入账后连接中断（响应读取失败 → 结果未定）。 */
        APPLIED_THEN_RESET,
        /** 入账后返回 500（服务端错误但已生效 → 结果未定）。 */
        APPLIED_THEN_HTTP_500,
        /** 核对查询本身失败（动作必须保持"结果未定"，不得猜结论）。 */
        RECONCILE_HTTP_500,
        /** 没有受控出站客户端：请求从未发出（确定未生效）。 */
        CLIENT_ABSENT,
        /** 上游明确拒绝 400（确定未生效）。 */
        HTTP_400
    }

    /** 业务事实：业务幂等键 → 已入账的收款记录。 */
    private final Map<String, Map<String, Object>> payments =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    /** 收到过的写请求参数（按顺序；用于断言"幂等键真的发给了上游"）。 */
    private final List<Map<String, Object>> receivedWrites = java.util.Collections.synchronizedList(new ArrayList<>());

    private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);

    private final AtomicInteger appliedCount = new AtomicInteger();

    private final AtomicInteger writeCalls = new AtomicInteger();

    private final AtomicInteger reconcileCalls = new AtomicInteger();

    /** 其余目标交给真实受控出站边界（允许清单为空 → 一律拒绝）。 */
    private final ExternalHttpClient realGuard;

    AiBusinessWriteSimulator() {
        AiHttpProperties properties = new AiHttpProperties();
        properties.setAllowedHosts(List.of());
        properties.setAllowedPorts(List.of(443));
        this.realGuard = new GuardedExternalHttpClient(properties);
    }

    void setFault(Fault next) {
        fault.set(next);
    }

    /** 清空业务事实与计数（每个用例开始前调用，避免相互影响）。 */
    void reset() {
        payments.clear();
        receivedWrites.clear();
        appliedCount.set(0);
        writeCalls.set(0);
        reconcileCalls.set(0);
        fault.set(Fault.NONE);
    }

    /**
     * 直接写入业务事实（夹具用）：模拟"业务系统里已经存在该业务键的记录"——
     * 例如崩溃前写已生效，或同一业务键被别的渠道登记过。
     */
    void applyDirectly(String paymentNo, int amount) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("payment_no", paymentNo);
        record.put("amount", amount);
        if (payments.putIfAbsent(paymentNo, record) == null) {
            appliedCount.incrementAndGet();
        }
    }

    /** 某业务键是否已入账。 */
    boolean applied(String paymentNo) {
        return payments.containsKey(paymentNo);
    }

    /** 入账次数（重复提交同一业务键只算一次）。 */
    int appliedCount() {
        return appliedCount.get();
    }

    int writeCalls() {
        return writeCalls.get();
    }

    int reconcileCalls() {
        return reconcileCalls.get();
    }

    List<Map<String, Object>> receivedWrites() {
        return List.copyOf(receivedWrites);
    }

    @Override
    public ExternalHttpResponse execute(ExternalHttpRequest request) {
        URI uri = URI.create(request.url());
        if (!SIMULATOR_ORIGIN.equals(uri.getScheme() + "://" + uri.getHost())) {
            // 不是模拟器目标：交给真实的受控出站边界（未授权目标在那里被拒绝）
            return realGuard.execute(request);
        }
        return route(request);
    }

    @Override
    public CompletableFuture<ExternalHttpResponse> executeAsync(ExternalHttpRequest request) {
        return CompletableFuture.supplyAsync(() -> execute(request));
    }

    @Override
    public void close() {
        realGuard.close();
    }

    private ExternalHttpResponse route(ExternalHttpRequest request) {
        String path = URI.create(request.url()).getPath();
        if ("POST".equals(request.method()) && "/payments".equals(path)) {
            return applyPayment(request);
        }
        if ("GET".equals(request.method()) && "/payments/query".equals(path)) {
            return queryPayment(request);
        }
        return json(404, Map.of("error", "not-found"));
    }

    /** 收款写入：业务幂等键是 payment_no —— 同键重复提交不再入账（返回 applied=false）。 */
    private ExternalHttpResponse applyPayment(ExternalHttpRequest request) {
        writeCalls.incrementAndGet();
        Map<String, Object> body = parseObject(request.body());
        receivedWrites.add(body);
        Fault current = fault.get();
        if (current == Fault.CLIENT_ABSENT) {
            throw new ExternalHttpException(ExternalHttpException.Reason.CONNECT_FAILED, "模拟器不可用");
        }
        if (current == Fault.HTTP_400) {
            // 上游明确拒绝：确定没有入账
            return json(400, Map.of("error", "invalid-argument"));
        }
        if (current == Fault.TIMEOUT_BEFORE_APPLY) {
            throw new ExternalHttpException(ExternalHttpException.Reason.TIMEOUT, "写请求超时（未入账）");
        }
        String paymentNo = body.get("payment_no") == null ? "" : String.valueOf(body.get("payment_no"));
        boolean firstApplication = payments.putIfAbsent(paymentNo, body) == null;
        if (firstApplication) {
            appliedCount.incrementAndGet();
        }
        if (current == Fault.APPLIED_THEN_TIMEOUT) {
            throw new ExternalHttpException(ExternalHttpException.Reason.TIMEOUT, "写请求超时（已入账）");
        }
        if (current == Fault.APPLIED_THEN_RESET) {
            throw new ExternalHttpException(ExternalHttpException.Reason.CONNECT_FAILED, "读取响应失败（已入账）");
        }
        if (current == Fault.APPLIED_THEN_HTTP_500) {
            return json(500, Map.of("error", "internal-error"));
        }
        return json(200, Map.of("payment_no", paymentNo, "applied", firstApplication));
    }

    /**
     * 核对查询：按业务幂等键查（查到即"已生效"，查不到即"未生效"）。
     *
     * <p>响应体是 {@code {"items": [...]}}：连接器按登记的提取路径 {@code items} 取结果列表，
     * 空列表=未生效，含记录=已生效（提取路径是操作的声明，见夹具 setup 里的 response_json）。
     */
    private ExternalHttpResponse queryPayment(ExternalHttpRequest request) {
        reconcileCalls.incrementAndGet();
        if (fault.get() == Fault.RECONCILE_HTTP_500) {
            return json(500, Map.of("error", "internal-error"));
        }
        String paymentNo = queryParameter(request.url(), "payment_no");
        Map<String, Object> found = paymentNo == null ? null : payments.get(paymentNo);
        List<Object> items = found == null ? List.of() : List.of(found);
        return json(200, Map.of("items", items));
    }

    private static String queryParameter(String url, String name) {
        String query = URI.create(url).getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && name.equals(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8))) {
                return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static Map<String, Object> parseObject(byte[] body) {
        if (body == null || body.length == 0) {
            return Map.of();
        }
        Map<String, Object> parsed = JsonUtils.parseObject(new String(body, StandardCharsets.UTF_8), Map.class);
        return parsed == null ? Map.of() : parsed;
    }

    private static ExternalHttpResponse json(int status, Map<String, Object> payload) {
        return body(status, payload);
    }

    private static ExternalHttpResponse body(int status, Object payload) {
        byte[] bytes = JsonUtils.toJsonString(payload).getBytes(StandardCharsets.UTF_8);
        return new ExternalHttpResponse(status, Map.of("content-type", "application/json"), bytes, bytes.length);
    }
}
