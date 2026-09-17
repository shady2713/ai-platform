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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
 */
public class GuardedExternalHttpClient implements ExternalHttpClient {

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
        try {
            return executeAsync(request).join();
        } catch (CompletionException exception) {
            throw mapFailure(exception.getCause() == null ? exception : exception.getCause());
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

        return httpClient
                .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(this::readBounded)
                .exceptionally(exception -> {
                    throw mapFailure(exception instanceof CompletionException ? exception.getCause() : exception);
                });
    }

    /** 把底层失败收敛为稳定错误：调用方不需要理解底层异常类型。 */
    // 包内可见以便契约测试直接覆盖失败映射
    static ExternalHttpException mapFailure(Throwable cause) {
        if (cause instanceof ExternalHttpException externalHttpException) {
            return externalHttpException;
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
