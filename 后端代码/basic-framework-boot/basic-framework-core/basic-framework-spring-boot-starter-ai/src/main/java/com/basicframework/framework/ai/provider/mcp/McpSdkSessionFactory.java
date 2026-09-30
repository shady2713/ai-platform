package com.basicframework.framework.ai.provider.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于官方 MCP Java SDK 2.0.1 的会话实现（X07）。
 *
 * <p>使用面被刻意压到最小：{@code initialize} 握手 + {@code tools/list}。
 * SDK 的 {@code McpSyncClient.callTool} 在本类<b>不出现</b>——远程工具只能经平台注册与审批
 * 进入执行面（见 {@link McpClientSession} 的说明）。
 *
 * <p>传输用 SDK 内置的 {@link HttpClientStreamableHttpTransport}（Streamable HTTP，MCP 2025-06-18
 * 起的标准传输），令牌通过请求定制器加在头里；地址准入在构造传输<b>之前</b>已由
 * {@link McpEndpointPolicy} 完成，本类不重复判定也不放宽。
 */
public class McpSdkSessionFactory implements McpSessionFactory {

    /** 端点路径：固定为 Streamable HTTP 的标准路径，不接受自由路径（避免把路径变成注入面）。 */
    private static final String ENDPOINT_PATH = "/mcp";

    /** 客户端身份：如实申报平台名与版本，便于上游做访问日志归属。 */
    private static final String CLIENT_NAME = "basic-framework-ai-platform";

    private static final String CLIENT_VERSION = "1.0";

    /**
     * JSON mapper：由 SDK 自带 supplier 创建（内部持有 ObjectMapper，jackson-databind 是
     * {@code mcp-json-jackson2} 的 compile 传递依赖）。记录组件不能有实例字段，故用静态常量。
     */
    private static final McpJsonMapper MAPPER = new JacksonMcpJsonMapperSupplier().get();

    private final List<String> supportedProtocolVersions;

    public McpSdkSessionFactory(List<String> supportedProtocolVersions) {
        this.supportedProtocolVersions =
                supportedProtocolVersions == null ? List.of() : List.copyOf(supportedProtocolVersions);
    }

    @Override
    public McpClientSession open(McpServerEndpoint endpoint, McpEndpointPolicy policy) {
        URI uri = policy.requireAllowed(endpoint.baseUri());
        String baseUri = uri.toString();
        if (baseUri.endsWith("/")) {
            baseUri = baseUri.substring(0, baseUri.length() - 1);
        }
        HttpClientStreamableHttpTransport.Builder transportBuilder = HttpClientStreamableHttpTransport.builder(baseUri)
                .endpoint(ENDPOINT_PATH)
                .connectTimeout(endpoint.connectTimeout())
                .jsonMapper(MAPPER);
        if (!supportedProtocolVersions.isEmpty()) {
            transportBuilder.supportedProtocolVersions(supportedProtocolVersions);
        }
        if (endpoint.hasAuthorization()) {
            // 令牌只在本次请求头里出现：不写日志、不进异常、不落盘
            transportBuilder.httpRequestCustomizer(new BearerHeaderCustomizer(endpoint.authorization()));
        }
        McpSyncClient client = McpClient.sync(transportBuilder.build())
                .requestTimeout(endpoint.requestTimeout())
                .clientInfo(new McpSchema.Implementation(CLIENT_NAME, CLIENT_VERSION))
                .build();
        try {
            client.initialize();
        } catch (RuntimeException failure) {
            closeQuietly(client);
            throw translate(failure);
        }
        return new SdkSession(client);
    }

    /** 把 SDK/JDK 异常收敛成稳定终止原因（不透出上游正文与主机名）。 */
    private static McpClientException translate(RuntimeException failure) {
        for (Throwable cursor = failure; cursor != null; cursor = nextCause(cursor)) {
            if (cursor instanceof java.net.http.HttpTimeoutException) {
                return new McpClientException(McpTermination.TIMEOUT, "MCP 请求超时");
            }
            if (cursor instanceof java.net.http.HttpConnectTimeoutException) {
                return new McpClientException(McpTermination.TIMEOUT, "MCP 连接超时");
            }
            if (cursor instanceof McpHttpClientTransportAuthorizationException) {
                return new McpClientException(McpTermination.UNAUTHORIZED, "MCP 授权被拒");
            }
            if (cursor instanceof UnknownHostException) {
                return new McpClientException(McpTermination.UNREACHABLE, "MCP 端点主机不可解析");
            }
            if (cursor instanceof java.net.ConnectException) {
                return new McpClientException(McpTermination.UNREACHABLE, "MCP 端点不可达");
            }
            if (cursor instanceof McpTransportException) {
                return new McpClientException(McpTermination.PROTOCOL_ERROR, "MCP 协议交互失败");
            }
        }
        // 未识别的失败一律按"不可读"收敛：把未知异常直接抛出去等于让上游决定平台的行为
        return new McpClientException(McpTermination.PROTOCOL_ERROR, "MCP 会话失败");
    }

    private static Throwable nextCause(Throwable current) {
        Throwable cause = current.getCause();
        return cause == current ? null : cause;
    }

    private static void closeQuietly(McpSyncClient client) {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // 握手已失败，关闭异常不覆盖原始结论
        }
    }

    /** 只加 Authorization 头，不触碰 Host/Content-Length 等传输层保留头。 */
    private record BearerHeaderCustomizer(String authorization) implements McpSyncHttpClientRequestCustomizer {

        @Override
        public void customize(
                java.net.http.HttpRequest.Builder builder,
                String method,
                URI uri,
                String body,
                McpTransportContext context) {
            builder.header("Authorization", authorization);
        }
    }

    /** 一次已握手的会话。 */
    private record SdkSession(McpSyncClient client) implements McpClientSession {

        @Override
        public String protocolVersion() {
            McpSchema.InitializeResult result = client.getCurrentInitializationResult();
            return result == null || result.protocolVersion() == null ? "" : result.protocolVersion();
        }

        @Override
        public String serverName() {
            McpSchema.Implementation info = client.getServerInfo();
            return info == null || info.name() == null ? "" : info.name();
        }

        @Override
        public List<McpToolDescriptor> listTools() {
            McpSchema.ListToolsResult result;
            try {
                result = client.listTools();
            } catch (RuntimeException failure) {
                throw translate(failure);
            }
            if (result == null || result.tools() == null) {
                throw new McpClientException(McpTermination.PROTOCOL_ERROR, "MCP 工具清单为空响应");
            }
            List<McpToolDescriptor> descriptors = new ArrayList<>(result.tools().size());
            for (McpSchema.Tool tool : result.tools()) {
                descriptors.add(new McpToolDescriptor(
                        tool.name(), tool.title(), tool.description(), renderSchema(tool.inputSchema())));
            }
            return descriptors;
        }

        /**
         * 输入 schema 渲染为 JSON 文本（指纹与人工审核比对的依据）。
         *
         * <p>渲染失败按"空 schema"处理：宁可让平台侧拒绝准入，也不把无法序列化的 schema
         * 当成合法结构放行。
         */
        private String renderSchema(Map<String, Object> inputSchema) {
            if (inputSchema == null) {
                return "";
            }
            try {
                return MAPPER.writeValueAsString(inputSchema);
            } catch (IOException unrenderable) {
                return "";
            }
        }

        @Override
        public void close() {
            closeQuietly(client);
        }
    }
}
