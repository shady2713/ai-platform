package com.basicframework.framework.ai.provider.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 官方 SDK 会话工厂的可验证契约（X07 逐步实施第 1 条的可执行部分）。
 *
 * <p>本类验证的是"平台能控制、且不依赖对端在线"的那部分契约：
 * 地址默认拒绝发生在**构造传输之前**、异常分类收敛、令牌只进 Authorization 头、
 * 端点声明的归一与夹紧、重试语义。
 *
 * <p><b>明确未覆盖</b>：与一台真实 MCP 服务器的端到端握手（initialize + tools/list）。
 * 手写 Streamable HTTP 假服务端未能满足 SDK 2.0.1 对 initialize 后 GET SSE 长连接的期望
 * （见证据文档"未验证项"）。因此本卡对"选型能编译、默认值受控、拒绝发生在网络动作之前"
 * 有执行证据，对"与真实 MCP 服务器完成一次握手"没有。
 */
class McpSdkSessionFactoryTest {

    private static final String PROTOCOL = "2025-06-18";

    private static McpEndpointPolicy allowAllPrivate() {
        return new McpEndpointPolicy(Set.of("127.0.0.1"), Set.of(18080), true);
    }

    @Test
    void factoryDeniesOutOfAllowlistAddressBeforeAnySocketIsOpened() {
        McpSdkSessionFactory factory = new McpSdkSessionFactory(List.of(PROTOCOL));
        // 端点端口 18080 不在允许端口清单 {443} 内：拒绝必须发生在构造传输与建连之前
        // （否则"默认拒绝"只是连上之后才报错，白名单形同虚设）
        McpEndpointPolicy httpsOnly = new McpEndpointPolicy(Set.of("127.0.0.1"), Set.of(443), true);
        McpServerEndpoint denied =
                new McpServerEndpoint("http://127.0.0.1:18080", null, Duration.ofSeconds(1), Duration.ofSeconds(1), 1);

        assertThatThrownBy(() -> factory.open(denied, httpsOnly))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.ADDRESS_DENIED));
    }

    @Test
    void factoryDeniesHostOutsideAllowlistBeforeAnySocketIsOpened() {
        McpSdkSessionFactory factory = new McpSdkSessionFactory(List.of(PROTOCOL));
        McpServerEndpoint denied = new McpServerEndpoint(
                "https://evil.example.org", null, Duration.ofSeconds(1), Duration.ofSeconds(1), 1);

        assertThatThrownBy(() -> factory.open(denied, allowAllPrivate()))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.ADDRESS_DENIED));
    }

    @Test
    void factoryDeniesAnonymousEndpointWhenPolicyRequiresHttpsOnly() {
        McpSdkSessionFactory factory = new McpSdkSessionFactory(List.of(PROTOCOL));
        McpEndpointPolicy httpsOnly = new McpEndpointPolicy(Set.of("mcp.example.com"), Set.of(443), false);
        McpServerEndpoint httpEndpoint =
                new McpServerEndpoint("http://mcp.example.com", null, Duration.ofSeconds(1), Duration.ofSeconds(1), 1);

        assertThatThrownBy(() -> factory.open(httpEndpoint, httpsOnly))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.ADDRESS_DENIED));
    }

    @Test
    void allowedEndpointReachesTransportConstructionAndFailsAtHandshakeWithAStableTermination() {
        // 地址放行 → 真正构造 SDK 传输与客户端 → 握手时连接失败 → 收敛成稳定终止原因。
        // 这条用例覆盖"默认拒绝放行之后的真实路径"，并证明失败不会泄漏主机名。
        int deadPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        } catch (java.io.IOException unavailable) {
            return;
        }
        McpSdkSessionFactory factory = new McpSdkSessionFactory(List.of(PROTOCOL));
        McpEndpointPolicy deadPolicy = new McpEndpointPolicy(Set.of("127.0.0.1"), Set.of(deadPort), true);
        // 基址带尾斜杠：走一遍"剥离尾斜杠免得拼出 //mcp"的归一分支
        McpServerEndpoint endpoint = new McpServerEndpoint(
                "http://127.0.0.1:" + deadPort + "/",
                "Bearer it-token",
                Duration.ofMillis(400),
                Duration.ofMillis(400),
                1);

        assertThatThrownBy(() -> factory.open(endpoint, deadPolicy))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> {
                    McpClientException cause = (McpClientException) failure;
                    assertThat(cause.termination())
                            .isIn(McpTermination.UNREACHABLE, McpTermination.TIMEOUT, McpTermination.PROTOCOL_ERROR);
                    assertThat(cause.retryable()).isTrue();
                    assertThat(cause.getMessage()).doesNotContain("127.0.0.1");
                });
    }

    /**
     * SDK 结果 → 平台描述符的映射（用 mock 的 {@code McpSyncClient}，不需要在线服务器）。
     *
     * <p>这一段是本卡真正自己写的逻辑（协议事实搬运 + 归一 + 指纹输入），
     * 值得单独钉住；线路握手那部分才依赖对端。
     */
    @Test
    void sdkResultsAreMappedIntoPlatformDescriptors() {
        io.modelcontextprotocol.client.McpSyncClient client =
                org.mockito.Mockito.mock(io.modelcontextprotocol.client.McpSyncClient.class);
        when(client.getCurrentInitializationResult())
                .thenReturn(new io.modelcontextprotocol.spec.McpSchema.InitializeResult(
                        PROTOCOL,
                        serverCapabilities(),
                        new io.modelcontextprotocol.spec.McpSchema.Implementation("srv", "1.0"),
                        null));
        when(client.getServerInfo())
                .thenReturn(new io.modelcontextprotocol.spec.McpSchema.Implementation("srv", "1.0"));
        when(client.listTools())
                .thenReturn(new io.modelcontextprotocol.spec.McpSchema.ListToolsResult(
                        List.of(new io.modelcontextprotocol.spec.McpSchema.Tool(
                                "search_orders",
                                "查询订单",
                                "描述",
                                Map.of("type", "object", "properties", Map.of("region", Map.of("type", "string"))),
                                null,
                                null,
                                null)),
                        null));

        McpClientSession session = newSdkSession(client);

        assertThat(session.protocolVersion()).isEqualTo(PROTOCOL);
        assertThat(session.serverName()).isEqualTo("srv");
        List<McpToolDescriptor> tools = session.listTools();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("search_orders");
        assertThat(tools.get(0).description()).isEqualTo("描述");
        assertThat(tools.get(0).inputSchemaJson()).contains("region");
        assertThat(tools.get(0).schemaFingerprint()).hasSize(64);

        session.close();
        org.mockito.Mockito.verify(client).close();
    }

    @Test
    void missingInitializationResultDegradesToEmptyStringsRatherThanThrowing() {
        // SDK 的 record 构造器对 protocolVersion/serverInfo/inputSchema 等字段断言非空，
        // 所以"字段为 null"的分支构造不出真实形态——只覆盖"协商结果整体为 null"这一条。
        io.modelcontextprotocol.client.McpSyncClient client =
                org.mockito.Mockito.mock(io.modelcontextprotocol.client.McpSyncClient.class);
        when(client.getCurrentInitializationResult()).thenReturn(null);
        when(client.getServerInfo()).thenReturn(null);

        McpClientSession session = newSdkSession(client);
        assertThat(session.protocolVersion()).isEmpty();
        assertThat(session.serverName()).isEmpty();
    }

    @Test
    void nullToolListIsAProtocolErrorAndToolListFailureIsTranslated() {
        io.modelcontextprotocol.client.McpSyncClient nullResult =
                org.mockito.Mockito.mock(io.modelcontextprotocol.client.McpSyncClient.class);
        when(nullResult.listTools()).thenReturn(null);
        assertThatThrownBy(() -> newSdkSession(nullResult).listTools())
                .isInstanceOf(McpClientException.class)
                .satisfies(f ->
                        assertThat(((McpClientException) f).termination()).isEqualTo(McpTermination.PROTOCOL_ERROR));

        io.modelcontextprotocol.client.McpSyncClient exploding =
                org.mockito.Mockito.mock(io.modelcontextprotocol.client.McpSyncClient.class);
        when(exploding.listTools()).thenThrow(new IllegalStateException("upstream said: token=sk-secret"));
        assertThatThrownBy(() -> newSdkSession(exploding).listTools())
                .isInstanceOf(McpClientException.class)
                .satisfies(f -> {
                    assertThat(((McpClientException) f).getMessage()).doesNotContain("sk-secret");
                });
    }

    /** 真实的服务端能力声明（{@code InitializeResult} 不接受 null capabilities）。 */
    private static io.modelcontextprotocol.spec.McpSchema.ServerCapabilities serverCapabilities() {
        return io.modelcontextprotocol.spec.McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build();
    }

    /** 反射构造工厂内部的会话记录（记录是私有的，且只在真实握手后才产生）。 */
    private static McpClientSession newSdkSession(io.modelcontextprotocol.client.McpSyncClient client) {
        try {
            Class<?> type = Class.forName(McpSdkSessionFactory.class.getName() + "$SdkSession");
            var constructor = type.getDeclaredConstructor(io.modelcontextprotocol.client.McpSyncClient.class);
            constructor.setAccessible(true);
            return (McpClientSession) constructor.newInstance(client);
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    @Test
    void transportExceptionClassificationCollapsesToStableTerminations() {
        // 分类逻辑是承重的：把各种传输异常收敛成可枚举、可断言、可映射错误码的终止原因
        assertThat(translateOf(wrapped(new java.net.http.HttpTimeoutException("t"))))
                .isEqualTo(McpTermination.TIMEOUT);
        assertThat(translateOf(wrapped(new java.net.http.HttpConnectTimeoutException("t"))))
                .isEqualTo(McpTermination.TIMEOUT);
        assertThat(translateOf(wrapped(new java.net.UnknownHostException("mcp.invalid.test"))))
                .isEqualTo(McpTermination.UNREACHABLE);
        assertThat(translateOf(wrapped(new java.net.ConnectException("Connection refused"))))
                .isEqualTo(McpTermination.UNREACHABLE);
        assertThat(translateOf(
                        new io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException(
                                "denied", null, null)))
                .isEqualTo(McpTermination.UNAUTHORIZED);
        assertThat(translateOf(new IllegalStateException("MCP-001: unexpected content type")))
                .isEqualTo(McpTermination.PROTOCOL_ERROR);
    }

    @Test
    void classificationNeverLeaksUpstreamDetailIntoTheMessage() {
        // 上游异常正文可能含内部路径/令牌片段：分类后只剩稳定原因，不带原文
        McpClientException translated =
                translateException(new IllegalStateException("upstream said: token=sk-secret at /srv/internal/config"));
        assertThat(translated.getMessage()).doesNotContain("sk-secret");
        assertThat(translated.getMessage()).doesNotContain("/srv/internal");
    }

    @Test
    void bearerCustomizerAddsOnlyTheAuthorizationHeader() {
        java.net.http.HttpRequest.Builder builder =
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:18080/mcp"));
        customizeAuthorization(builder, "Bearer only-auth");

        java.net.http.HttpRequest request = builder.build();
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer only-auth");
        // 只加一个头：不注入 Host/Content-Length 等传输层保留头（它们由 HTTP 客户端自己管）
        assertThat(request.headers().map()).hasSize(1);
    }

    @Test
    void noCustomizerIsInstalledWhenTheEndpointDeclaresNoAuthorization() {
        // 生产路径只在 hasAuthorization() 为真时安装定制器：匿名端点因此不会带上一个空的
        // Authorization 头（那会被上游当成"提供了凭据但为空"，比不带更糟）
        McpServerEndpoint anonymous =
                new McpServerEndpoint("https://mcp.example.com", null, Duration.ofSeconds(1), Duration.ofSeconds(1), 1);
        assertThat(anonymous.hasAuthorization()).isFalse();
        assertThat(new McpServerEndpoint("https://mcp.example.com", "   ", null, null, 1).hasAuthorization())
                .isFalse();
        assertThat(new McpServerEndpoint("https://mcp.example.com", "Bearer x", null, null, 1).hasAuthorization())
                .isTrue();
    }

    @Test
    void endpointNormalizesUnsetTimeoutsAndAttemptCounts() {
        McpServerEndpoint bare = new McpServerEndpoint("https://mcp.example.com", "   ", null, null, 1);
        assertThat(bare.hasAuthorization()).isFalse();
        // 缺失超时收敛到默认值（不把"未配置"变成 0 毫秒立即失败）
        assertThat(bare.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(bare.requestTimeout()).isEqualTo(Duration.ofSeconds(60));

        McpServerEndpoint hostile = new McpServerEndpoint(
                "https://mcp.example.com", "Bearer x", Duration.ofMillis(-1), Duration.ofHours(1), 99);
        assertThat(hostile.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        // 超大请求超时被硬上限夹住：否则等于"实际上不超时"
        assertThat(hostile.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
        // 重试上界由类型夹紧，不靠调用方自觉
        assertThat(hostile.maxAttempts()).isEqualTo(McpServerEndpoint.MAX_ATTEMPTS_CEILING);
        assertThat(hostile.hasAuthorization()).isTrue();
    }

    @Test
    void endpointTrimsAndNormalizesBaseUri() {
        assertThat(new McpServerEndpoint("  https://mcp.example.com  ", null, null, null, 1).baseUri())
                .isEqualTo("https://mcp.example.com");
        assertThat(new McpServerEndpoint(null, null, null, null, 1).baseUri()).isEmpty();
    }

    @Test
    void exceptionRetrySemanticsDistinguishTransientFromPermanentFailures() {
        assertThat(new McpClientException(McpTermination.TIMEOUT, "t").retryable())
                .isTrue();
        assertThat(new McpClientException(McpTermination.UNREACHABLE, "t").retryable())
                .isTrue();
        assertThat(new McpClientException(McpTermination.UNAUTHORIZED, "t").retryable())
                .isFalse();
        assertThat(new McpClientException(McpTermination.PROTOCOL_ERROR, "t").retryable())
                .isFalse();
        assertThat(new McpClientException(McpTermination.ADDRESS_DENIED, "t").retryable())
                .isFalse();
        assertThat(new McpClientException(McpTermination.RESPONSE_TOO_LARGE, "t").retryable())
                .isFalse();
        assertThat(new McpClientException(McpTermination.PROTOCOL_VERSION_UNSUPPORTED, "t").retryable())
                .isFalse();

        // 归一：终止原因缺失按最严格处理；尝试次数至少为 1（"没试过就失败"不是本类要表达的状态）
        McpClientException bare = new McpClientException(null, "x", 0);
        assertThat(bare.termination()).isEqualTo(McpTermination.PROTOCOL_ERROR);
        assertThat(bare.attempts()).isEqualTo(1);
    }

    @Test
    void exceptionCarriesNoUpstreamStackTrace() {
        // 异常不保留上游栈：栈里常含内部类名与路径，跨边界传播等于泄漏实现细节
        McpClientException failure = new McpClientException(McpTermination.PROTOCOL_ERROR, "boom");
        assertThat(failure.getStackTrace()).isEmpty();
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void factoryToleratesNullProtocolVersionList() {
        // 构造器归一：null 版本清单不抛异常（是否放行由平台侧的允许清单判定）
        assertThat(new McpSdkSessionFactory(null)).isNotNull();
        assertThat(new McpSdkSessionFactory(List.of())).isNotNull();
    }

    private static RuntimeException wrapped(Exception cause) {
        return new IllegalStateException("sdk transport failed", cause);
    }

    /** 反射调用工厂的私有映射方法：分类逻辑是承重的，值得单独钉住。 */
    private static McpTermination translateOf(RuntimeException failure) {
        return translateException(failure).termination();
    }

    private static McpClientException translateException(RuntimeException failure) {
        try {
            Method method = McpSdkSessionFactory.class.getDeclaredMethod("translate", RuntimeException.class);
            method.setAccessible(true);
            return (McpClientException) method.invoke(null, failure);
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** 反射拿到 SDK 工厂内部的定制器记录，直接验证"只加一个头"。 */
    private static void customizeAuthorization(java.net.http.HttpRequest.Builder builder, String authorization) {
        try {
            Class<?> customizerType = Class.forName(
                    "io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer");
            Class<?> recordType = Class.forName(McpSdkSessionFactory.class.getName() + "$BearerHeaderCustomizer");
            var constructor = recordType.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            Object customizer = constructor.newInstance(authorization);
            Method customize = customizerType.getMethod(
                    "customize",
                    java.net.http.HttpRequest.Builder.class,
                    String.class,
                    java.net.URI.class,
                    String.class,
                    io.modelcontextprotocol.common.McpTransportContext.class);
            customize.invoke(customizer, builder, "POST", builder.build().uri(), null, null);
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
