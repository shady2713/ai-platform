package com.basicframework.framework.ai.core.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 受控出站 HTTP 客户端（F09）。
 *
 * <p>三道闸门，任一不满足即拒绝（fail-closed）：
 * <ol>
 *   <li><b>目标允许清单</b>：主机必须精确命中 {@code allowedHosts}，端口必须命中 {@code allowedPorts}；
 *       清单为空时拒绝一切目标。</li>
 *   <li><b>地址校验</b>：解析目标的所有地址，命中环回/链路本地/站点本地/唯一本地/组播/未指定地址时，
 *       必须显式开启 {@code allowPrivateTargets} 才放行（企业内网模型与连接器需要该开关）。</li>
 *   <li><b>请求卫生</b>：请求头只能由服务端构造；Host/Connection/Content-Length/Transfer-Encoding/Cookie
 *       等传输层与宿主凭据头一律拒绝，头部数量有上限。</li>
 * </ol>
 *
 * <p>其余边界：不跟随重定向（3xx 原样返回，是否改址由业务决策）、TLS 使用 JVM 默认校验（不提供关闭入口）、
 * 连接与读取超时、响应体上限（超过即中断）、{@code close()} 后拒绝新请求。
 *
 * <p>已知残余风险：DNS 校验与实际连接之间存在解析结果变化的窗口（TOCTOU）。当前通过“允许清单 + 私网显式批准”
 * 收窄影响面；需要更强保证时应在网络层做出口网关限制，见 {@code docs/security/outbound-http-boundary.md}。
 *
 * <p>F11：同时提供 {@link ExternalHttpStreamSupport} 流式入口，与请求/响应入口**并存**。两个入口共用
 * {@link #prepare} 做全部请求期判定（关闭检查、请求卫生、允许清单、私网判定、协议与超时），
 * 因此流式请求的治理面与请求/响应请求**逐条相同**；差别只在响应体逐块交付、超限改为终止流。
 *
 * <p>F12：失败归因区分**调用方取消**（{@link ExternalHttpException.Reason#CANCELLED}）与
 * **连接失败**。取消是调用方自己的决定，报成 {@code CONNECT_FAILED} 会把排查方向引到网络与上游。
 * 两个阻塞入口共用 {@link #await}，失败最终都收敛到 {@link #mapFailure}，
 * 因此"同步可区分、流式不可区分"不会发生。
 */
public class GuardedExternalHttpClient implements ExternalHttpClient, ExternalHttpStreamSupport {

    /** 不允许出现在出站请求里的头：传输层头与宿主凭据头。 */
    private static final Set<String> BLOCKED_HEADERS =
            Set.of("host", "connection", "content-length", "transfer-encoding", "cookie", "set-cookie");

    private final AiHttpProperties properties;

    private final HttpClient httpClient;

    private final ExecutorService executor;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public GuardedExternalHttpClient(AiHttpProperties properties) {
        this.properties = properties;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "ai-external-http");
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build();
    }

    @Override
    public ExternalHttpResponse execute(ExternalHttpRequest request) {
        return await(executeAsync(request));
    }

    /**
     * 两个阻塞入口（{@link #execute} 与 {@link #openStream}）共用的等待与归因。
     *
     * <p>只有一处映射入口，是"同步与流式对同一个失败给出同一个归因"的结构性保证：
     * 否则很容易出现"同步已可区分、流式仍不可区分"。
     *
     * <p>{@code join()} 在 future 被取消时直接抛 {@link CancellationException}（不包
     * {@code CompletionException}），所以两种包装都要接住。包内可见以便直接覆盖取消这条路径。
     */
    static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            throw mapFailure(exception.getCause() == null ? exception : exception.getCause());
        } catch (CancellationException exception) {
            throw mapFailure(exception);
        }
    }

    @Override
    public CompletableFuture<ExternalHttpResponse> executeAsync(ExternalHttpRequest request) {
        try {
            return send(request);
        } catch (ExternalHttpException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletableFuture<ExternalHttpResponse> send(ExternalHttpRequest request) {
        return httpClient
                .sendAsync(prepare(request), HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(this::readBounded)
                .exceptionally(exception -> {
                    throw mapFailure(exception instanceof CompletionException ? exception.getCause() : exception);
                });
    }

    /**
     * 流式入口（F11）：请求期判定与 {@link #send} 共用 {@link #prepare}，因此流式请求同样在
     * 发出任何字节之前完成允许清单、私网判定、协议、请求头卫生与超时判定；被拒时请求不离开进程。
     *
     * <p>响应体不再整体缓冲：返回的 {@link ExternalHttpStreamResponse} 由调用方逐块读取，
     * 累计超过 {@code max-response-bytes} 时以 {@code RESPONSE_TOO_LARGE} 终止流
     * （此前已交付的块不回收成完整结果）。调用方必须关闭它以释放上游连接。
     */
    @Override
    public ExternalHttpStreamResponse openStream(ExternalHttpRequest request) {
        HttpResponse<InputStream> response =
                await(httpClient.sendAsync(prepare(request), HttpResponse.BodyHandlers.ofInputStream()));
        return new BoundedExternalHttpStreamResponse(
                response.statusCode(),
                flatten(response.headers().map()),
                response.headers().firstValueAsLong("content-length").orElse(-1L),
                properties.getMaxResponseBytes(),
                response.body());
    }

    /**
     * 两个入口共用的请求期闸门：关闭检查 → 请求卫生 → 允许清单 → 私网判定 → 协议与端口 → 超时与请求体。
     *
     * <p>流式入口复用本方法，是“流式仍受同一份治理”的结构性保证，而不是靠两处代码同步维护。
     */
    private HttpRequest prepare(ExternalHttpRequest request) {
        if (closed.get()) {
            throw new ExternalHttpException(ExternalHttpException.Reason.INVALID_REQUEST, "出站客户端已关闭");
        }
        URI uri = validateRequest(request);
        validateTargetAddresses(uri.getHost());

        Duration timeout = request.timeout() == null ? properties.getReadTimeout() : request.timeout();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        if (request.body() == null) {
            builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
        }
        request.headers().forEach(builder::header);
        return builder.build();
    }

    /** 把底层失败收敛为稳定错误：调用方不需要理解底层异常类型。 */
    // 包内可见以便契约测试直接覆盖失败映射
    static ExternalHttpException mapFailure(Throwable cause) {
        if (cause instanceof ExternalHttpException externalHttpException) {
            return externalHttpException;
        }
        // F12：取消必须与连接失败分开归因。放在超时与连接判定之前是语义要求——
        // 取消是调用方的决定，与网络无关，报成 CONNECT_FAILED 会把排查方向引到网络与上游。
        if (isCancellation(cause)) {
            return new ExternalHttpException(ExternalHttpException.Reason.CANCELLED, "出站请求已被调用方取消", cause);
        }
        if (cause instanceof HttpTimeoutException) {
            return new ExternalHttpException(ExternalHttpException.Reason.TIMEOUT, "出站请求超时", cause);
        }
        if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
            return new ExternalHttpException(ExternalHttpException.Reason.CONNECT_FAILED, "无法连接出站目标", cause);
        }
        // 未知失败也收敛为稳定错误
        return new ExternalHttpException(ExternalHttpException.Reason.CONNECT_FAILED, "出站请求失败", cause);
    }

    /** 因果链解包上限：只用来防止自引用异常导致死循环，不是"追到底"。 */
    private static final int MAX_CAUSE_DEPTH = 8;

    /**
     * 是否为调用方取消。{@link CompletionException} / {@link ExecutionException} 只是包装，
     * 逐层剥掉后再判定；<b>不沿其它类型的因果链搜索</b>——否则一个真正的 IO 失败
     * 只要链上挂着取消就会被误判成取消，正是本卡要避免的另一种错误归因。
     */
    private static boolean isCancellation(Throwable cause) {
        Throwable current = cause;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof CancellationException) {
                return true;
            }
            if (!(current instanceof CompletionException) && !(current instanceof ExecutionException)) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }

    private URI validateRequest(ExternalHttpRequest request) {
        if (request == null || request.method() == null || request.method().isBlank() || request.url() == null) {
            throw new ExternalHttpException(ExternalHttpException.Reason.INVALID_REQUEST, "请求缺少方法或地址");
        }
        if (request.headers().size() > properties.getMaxHeaderCount()) {
            throw new ExternalHttpException(ExternalHttpException.Reason.INVALID_REQUEST, "请求头数量超过上限");
        }
        for (String header : request.headers().keySet()) {
            if (BLOCKED_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
                throw new ExternalHttpException(
                        ExternalHttpException.Reason.INVALID_REQUEST, "不允许出站请求携带传输层或宿主凭据头：" + header);
            }
        }
        URI uri;
        try {
            uri = URI.create(request.url());
        } catch (IllegalArgumentException exception) {
            throw new ExternalHttpException(ExternalHttpException.Reason.INVALID_REQUEST, "地址格式不合法", exception);
        }
        if (uri.getHost() == null) {
            throw new ExternalHttpException(ExternalHttpException.Reason.INVALID_REQUEST, "地址缺少主机名");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        // https 始终允许；http 只在显式批准内网目标时允许
        if (!"https".equals(scheme) && !("http".equals(scheme) && properties.isAllowPrivateTargets())) {
            throw new ExternalHttpException(
                    ExternalHttpException.Reason.TARGET_NOT_ALLOWED, "仅允许 https（内网批准目标可用 http）");
        }
        if (!properties.getAllowedHosts().contains(uri.getHost())) {
            throw new ExternalHttpException(ExternalHttpException.Reason.TARGET_NOT_ALLOWED, "目标主机不在允许清单内");
        }
        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        if (!properties.getAllowedPorts().contains(port)) {
            throw new ExternalHttpException(ExternalHttpException.Reason.TARGET_NOT_ALLOWED, "目标端口不在允许清单内");
        }
        return uri;
    }

    private void validateTargetAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new ExternalHttpException(ExternalHttpException.Reason.CONNECT_FAILED, "目标域名无法解析", exception);
        }
        rejectBlockedAddresses(addresses, properties.isAllowPrivateTargets());
    }

    /**
     * 私网/环回等地址未显式批准时拒绝。分支集中在这个纯函数里，
     * 包内可见以便直接覆盖"批准放行 / 公网通过 / 私网拒绝"三条路径。
     */
    static void rejectBlockedAddresses(InetAddress[] addresses, boolean allowPrivateTargets) {
        if (allowPrivateTargets) {
            return;
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new ExternalHttpException(
                        ExternalHttpException.Reason.PRIVATE_TARGET_DENIED, "目标解析到私网或环回地址且未被显式批准");
            }
        }
    }

    /**
     * 是否属于必须显式批准才能访问的地址：环回、链路本地、站点本地、唯一本地（IPv6 ULA，fc00::/7）、
     * 未指定与组播。包内可见以便直接覆盖各类地址判定。
     */
    static boolean isBlockedAddress(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address);
    }

    /** IPv6 唯一本地地址（ULA，fc00::/7）：IPv4 没有对应 API，这里按首字节判断。 */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        return address instanceof java.net.Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
    }

    // 包内可见以便用桩响应确定性覆盖 IO 失败与丢弃分支
    ExternalHttpResponse readBounded(HttpResponse<InputStream> response) {
        int limit = properties.getMaxResponseBytes();
        long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
        if (declared > limit) {
            drain(response.body());
            throw new ExternalHttpException(ExternalHttpException.Reason.RESPONSE_TOO_LARGE, "响应体超过上限");
        }
        try (InputStream stream = response.body()) {
            byte[] body = stream.readNBytes(limit + 1);
            if (body.length > limit) {
                throw new ExternalHttpException(ExternalHttpException.Reason.RESPONSE_TOO_LARGE, "响应体超过上限");
            }
            return new ExternalHttpResponse(
                    response.statusCode(), flatten(response.headers().map()), body, declared);
        } catch (IOException exception) {
            throw new ExternalHttpException(ExternalHttpException.Reason.CONNECT_FAILED, "读取响应失败", exception);
        }
    }

    private static void drain(InputStream stream) {
        try (stream) {
            stream.readNBytes(1);
        } catch (IOException ignored) {
            // 超限响应直接丢弃：读取失败不影响拒绝语义
        }
    }

    private static Map<String, String> flatten(Map<String, List<String>> headers) {
        return headers.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> String.join(", ", entry.getValue()), (left, right) -> left));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }
}
