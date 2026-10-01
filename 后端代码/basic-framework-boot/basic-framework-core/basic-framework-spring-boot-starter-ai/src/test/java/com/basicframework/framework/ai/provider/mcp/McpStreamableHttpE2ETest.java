package com.basicframework.framework.ai.provider.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;

/**
 * 与真实 MCP 服务器的端到端握手，<b>走生产实际使用的传输</b>：Streamable HTTP。
 *
 * <p>{@link McpHandshakeE2ETest} 已经用 SDK 自带服务端（stdio 子进程）证明了<b>协议层</b>通，
 * 但生产代码 {@link McpSdkSessionFactory} 用的是 {@link HttpClientStreamableHttpTransport}
 * （Streamable HTTP，MCP 2025-06-18 起的标准传输），它的对端此前<b>从未被验证过</b>。
 * 本类补的就是这一段：对端同样是<b>官方 SDK 自己的服务端实现</b>
 * （{@link McpSyncServer} + {@link HttpServletStreamableServerTransportProvider}），
 * 挂在<b>真实嵌入式 Servlet 容器</b>（Tomcat，随机端口）上对外服务；
 * 本侧是与生产同款的 {@link HttpClientStreamableHttpTransport} 驱动的真实 {@link McpSyncClient}。
 * 两端同源同版本（mcp-core 2.0.1），跑的是真的 {@code initialize} 握手与真的 {@code tools/list} 往返。
 *
 * <p><b>为什么这次的证据比 stdio 更贴生产</b>：Streamable HTTP 与 stdio 的差别不在 JSON-RPC 本身
 * （那部分由 {@link McpHandshakeE2ETest} 覆盖），而在<b>会话是怎么被建立与拆掉的</b>——
 * 服务端在 {@code initialize} 响应里下发 {@code Mcp-Session-Id}，后续请求必须带回去，
 * 关闭必须走 {@code DELETE}。这些只有真的过一个 HTTP 容器才验得到。
 * 因此本类额外挂了一个 {@link RecordingValve}（容器级、在被测 servlet <b>之下</b>）：
 * 它记录的是 Tomcat 真的收发的请求，与 SDK 内部状态无关，因此不是同义反复。
 *
 * <p><b>依赖边界</b>：{@code mcp-core} 把 {@code jakarta.servlet-api} 声明为 {@code provided} 作用域
 * （不传递），上一轮实测本模块连编译都过不了。本类能跑是因为 X07 §2.2 授权在
 * <b>test 作用域</b>补了 {@code jakarta.servlet-api} 与 {@code tomcat-embed-core}
 * （版本取自仓库既有 BOM，未新造版本号）。生产依赖树不含这两条。
 *
 * <p>仍然刻意不碰 {@code callTool}：本卡只做发现，"发现"在类型上无法变成"执行"。
 */
class McpStreamableHttpE2ETest {

    /** 端点路径：与生产 {@link McpSdkSessionFactory} 的 {@code ENDPOINT_PATH} 同值。 */
    private static final String ENDPOINT_PATH = "/mcp";

    /** 握手请求超时：容器冷启动要留余量，但仍是上界。 */
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    /** Streamable HTTP 的会话头：服务端在 initialize 响应里下发，后续请求必须带回去。 */
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    @Test
    void initializeOverStreamableHttpEstablishesASessionAndNegotiatesTheProtocol() {
        Fixture fixture = Fixture.start();

        McpSyncClient client = fixture.client();
        assertThat(client.isInitialized()).isTrue();

        McpSchema.InitializeResult initialization = fixture.initialization();
        assertThat(initialization).isNotNull();
        // 显式握手拿到的结果与客户端自己记录的一致：不是"恰好没报错"
        assertThat(client.getCurrentInitializationResult()).isEqualTo(initialization);
        // 协议版本钉成字面量，不抄 SDK 常量：抄常量等于改错实现时测试跟着一起错、永远绿。
        assertThat(initialization.protocolVersion()).isEqualTo("2025-11-25");
        assertThat(fixture.supportedProtocolVersions()).contains(initialization.protocolVersion());
        assertThat(initialization.serverInfo().name()).isEqualTo(FixtureServer.SERVER_NAME);
        assertThat(initialization.serverInfo().version()).isEqualTo(FixtureServer.SERVER_VERSION);
        assertThat(client.getServerInfo().name()).isEqualTo(FixtureServer.SERVER_NAME);
        // 服务端必须真的宣告了 tools 能力，否则后面的 tools/list 不构成能力协商的证据
        assertThat(initialization.capabilities().tools()).isNotNull();

        fixture.close();
    }

    @Test
    void theInitializeExchangeReallyCrossedAnHttpContainerAndOpenedTheEventStream() {
        Fixture fixture = Fixture.start();

        // 不看 SDK 自己的字段，只看 Tomcat 真的收到了什么：握手必须是真实的 HTTP 往返。
        // 观测点在容器管道上、在被测 servlet 之下，与 SDK 传输不共用实现，因此不是同义反复。
        List<RecordedRequest> observed = fixture.observedRequests();
        assertThat(observed).hasSizeGreaterThanOrEqualTo(2);

        RecordedRequest initialize = observed.get(0);
        assertThat(initialize.method()).isEqualTo("POST");
        assertThat(initialize.requestUri()).isEqualTo(ENDPOINT_PATH);
        assertThat(initialize.status()).isEqualTo(200);
        // 首个请求必然还没有会话号——会话号正是这一步的响应才下发的
        assertThat(initialize.sessionId()).isNull();
        // Streamable HTTP 要求 initialize 同时接受 JSON 响应与 SSE 流，两者缺一不可
        assertThat(initialize.accept()).contains("application/json", "text/event-stream");

        // 会话号随 initialize 响应下发，不是 SDK 内部凭空记的
        String sessionId = observed.get(1).sessionId();
        assertThat(sessionId).as("服务端应在 initialize 之后下发会话号").isNotBlank();

        // initialize 之后客户端必须另开一条 GET SSE 长连接。这是 Streamable HTTP 相对 stdio
        // 与普通 JSON-RPC POST 的关键差异：没有它，就说明这条链路根本没在跑 Streamable HTTP。
        assertThat(observed).anySatisfy(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.requestUri()).isEqualTo(ENDPOINT_PATH);
            assertThat(request.accept()).isEqualTo("text/event-stream");
            assertThat(request.status()).isEqualTo(200);
            assertThat(request.sessionId()).isEqualTo(sessionId);
        });

        // 会话粘性：initialize 之后的每个请求都必须把同一个会话号带回去。缺了就等于
        // 每次请求都开新会话，Streamable HTTP 的会话管理形同虚设。
        assertThat(observed.subList(1, observed.size()))
                .allSatisfy(request -> assertThat(request.sessionId()).isEqualTo(sessionId));

        fixture.close();
    }

    @Test
    void listToolsReturnsExactlyTheRegisteredToolWithAnUnchangedInputSchema() {
        Fixture fixture = Fixture.start();

        McpSchema.ListToolsResult result = fixture.client().listTools();

        assertThat(result).isNotNull();
        assertThat(result.tools()).hasSize(1);
        McpSchema.Tool tool = result.tools().get(0);
        assertThat(tool.name()).isEqualTo(FixtureServer.TOOL_NAME);
        assertThat(tool.description()).isEqualTo(FixtureServer.TOOL_DESCRIPTION);
        // 入参 schema 逐字段核对：发现侧要靠它算指纹，任何增删或归一化都会让指纹失真。
        // 断言结构而不只是"与注册值相等"：相等是同义反复，这里钉住真实形状与内容。
        assertThat(tool.inputSchema()).containsEntry("type", "object").containsEntry("required", List.of("warehouse"));
        assertThat(castMap(tool.inputSchema().get("properties")))
                .containsOnlyKeys("warehouse", "skus")
                .containsEntry("warehouse", Map.of("type", "string", "description", "仓库编码"))
                .containsEntry(
                        "skus", Map.of("type", "array", "description", "SKU 列表", "items", Map.of("type", "string")));
        // schema 没被 SDK 偷偷塞进别的顶层键
        assertThat(tool.inputSchema()).containsOnlyKeys("type", "properties", "required");

        fixture.close();
    }

    @Test
    void closingTheSessionDeletesTheServerSideSessionAndShutsTheContainerDown() {
        Fixture fixture = Fixture.start();
        assertThat(fixture.client().listTools().tools()).hasSize(1);
        int port = fixture.port();

        fixture.close();

        // 关闭必须走 Streamable HTTP 的 DELETE（带会话号），而不是只关客户端 socket：
        // 这是本传输的会话终止动作，stdio 上根本没有对应物。
        List<RecordedRequest> observed = fixture.observedRequests();
        RecordedRequest last = observed.get(observed.size() - 1);
        assertThat(last.method()).isEqualTo("DELETE");
        assertThat(last.requestUri()).isEqualTo(ENDPOINT_PATH);
        assertThat(last.sessionId()).isNotBlank();
        assertThat(observed.subList(0, observed.size() - 1))
                .allSatisfy(request -> assertThat(request.method()).isNotEqualTo("DELETE"));
        // DELETE 带的就是本次握手建立的那个会话：会话粘性在拆除侧同样成立
        assertThat(last.sessionId()).isEqualTo(fixture.observedRequests().get(1).sessionId());

        // 容器真的停了：端口不再接受连接。这一条不能用 SDK 的状态代替，SDK 说"关了"时
        // Tomcat 仍可能在监听，那样下一个用例会撞端口。
        assertThat(refusesConnections(port)).as("容器应已停止监听端口 %d", port).isTrue();
        // 重复关闭必须幂等：平台的会话关闭路径允许被多次调用
        assertThatCode(fixture::closeAgain).doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** 端口是否已拒绝新连接（容器停止后的判据）。 */
    private static boolean refusesConnections(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    /** 容器层面观测到的一次请求（与 SDK 内部状态无关，故不是同义反复）。 */
    private record RecordedRequest(String method, String requestUri, String sessionId, String accept, int status) {}

    /**
     * 记录真实 HTTP 交换的 Tomcat Valve，挂在被测 servlet <b>之下</b>的管道上。
     *
     * <p>选容器级而不是包一层 servlet 的原因：SDK 的 {@code HttpServletStreamableServerTransportProvider}
     * 构造器是 {@code private}，子类化不了；而 Valve 只依赖容器接口，既能观测到
     * SDK 看不到的协议细节（会话头、DELETE），又不会和被测代码共用任何实现。
     */
    private static final class RecordingValve implements Valve {

        private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());

        private volatile Valve next;

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            requests.add(new RecordedRequest(
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getHeader(SESSION_HEADER),
                    request.getHeader("Accept"),
                    response.getStatus()));
            next.invoke(request, response);
            // 状态码在链走完才是终值（POST/DELETE 同步写完；GET 的 SSE 流是异步的，只记到此刻）
            int index = requests.size() - 1;
            RecordedRequest before = requests.get(index);
            if (before.status() != response.getStatus()) {
                requests.set(
                        index,
                        new RecordedRequest(
                                before.method(),
                                before.requestUri(),
                                before.sessionId(),
                                before.accept(),
                                response.getStatus()));
            }
        }

        @Override
        public Valve getNext() {
            return next;
        }

        @Override
        public void setNext(Valve next) {
            this.next = next;
        }

        @Override
        public void backgroundProcess() {
            // 无需后台任务：只观测请求，不做周期处理
        }

        @Override
        public boolean isAsyncSupported() {
            return true;
        }
    }

    /**
     * HTTP 夹具的工具与服务端身份。
     *
     * <p>刻意用与 stdio 夹具<b>不同</b>的工具与 schema（多一个数组属性）：两个用例的断言各自钉住
     * 自己的注册值，任一侧的注册被改坏时只会让自己的用例变红，不会互相掩盖。
     */
    private static final class FixtureServer {

        static final String SERVER_NAME = "x07-http-fixture-server";

        static final String SERVER_VERSION = "1.0";

        static final String TOOL_NAME = "mcp-query-inventory";

        static final String TOOL_DESCRIPTION = "按仓库查询库存批次（X07 Streamable HTTP 端到端夹具）";

        private static final Map<String, Object> TOOL_SCHEMA = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "warehouse",
                        Map.of("type", "string", "description", "仓库编码"),
                        "skus",
                        Map.of("type", "array", "description", "SKU 列表", "items", Map.of("type", "string"))),
                "required",
                List.of("warehouse"));

        private FixtureServer() {}

        static McpServerFeatures.SyncToolSpecification toolSpecification() {
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(TOOL_NAME)
                    .description(TOOL_DESCRIPTION)
                    .inputSchema(TOOL_SCHEMA)
                    .build();
            return new McpServerFeatures.SyncToolSpecification(
                    tool,
                    (McpSyncServerExchange exchange, McpSchema.CallToolRequest request) ->
                            McpSchema.CallToolResult.builder()
                                    .addTextContent("x07-http-e2e-fixture")
                                    .isError(Boolean.FALSE)
                                    .build());
        }
    }

    /** 一次"嵌入式 Tomcat + SDK 服务端 + 进程内客户端"的组合，负责起停与资源回收。 */
    private static final class Fixture {

        private final Tomcat tomcat;

        private final RecordingValve valve;

        private final McpSyncServer server;

        private final HttpClientStreamableHttpTransport transport;

        private final McpSyncClient client;

        private final McpSchema.InitializeResult initialization;

        private final int port;

        private Fixture(
                Tomcat tomcat,
                RecordingValve valve,
                McpSyncServer server,
                HttpClientStreamableHttpTransport transport,
                McpSyncClient client,
                McpSchema.InitializeResult initialization,
                int port) {
            this.tomcat = tomcat;
            this.valve = valve;
            this.server = server;
            this.transport = transport;
            this.client = client;
            this.initialization = initialization;
            this.port = port;
        }

        static Fixture start() {
            McpJsonMapper mapper = new JacksonMcpJsonMapperSupplier().get();
            Path baseDir = createTempDir();
            // 服务端传输没有 public 构造器，只能走 builder()；端点必须显式声明（build 时断言非空）
            HttpServletStreamableServerTransportProvider provider =
                    HttpServletStreamableServerTransportProvider.builder()
                            .jsonMapper(mapper)
                            .mcpEndpoint(ENDPOINT_PATH)
                            .build();
            McpSyncServer server = McpServer.sync(provider)
                    .serverInfo(FixtureServer.SERVER_NAME, FixtureServer.SERVER_VERSION)
                    .tools(FixtureServer.toolSpecification())
                    .build();

            RecordingValve valve = new RecordingValve();
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir(baseDir.toString());
            // 端口 0 = 由内核分配随机端口：不硬编码，避免并行/残留进程互相撞端口
            tomcat.setPort(0);
            Context context = tomcat.addContext("", baseDir.toString());
            context.getPipeline().addValve(valve);
            Tomcat.addServlet(context, "mcp", provider);
            context.addServletMappingDecoded(ENDPOINT_PATH + "/*", "mcp");
            startContainer(tomcat);
            int port = tomcat.getConnector().getLocalPort();
            assertThat(port).as("容器应绑定到内核分配的随机端口").isGreaterThan(0);

            // 客户端按生产同款方式构造：同一个传输类型、同一个固定端点路径
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(
                            "http://127.0.0.1:" + port)
                    .endpoint(ENDPOINT_PATH)
                    .connectTimeout(HANDSHAKE_TIMEOUT)
                    .jsonMapper(mapper)
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(HANDSHAKE_TIMEOUT)
                    .clientInfo(new McpSchema.Implementation("x07-http-e2e-client", "1.0"))
                    .build();
            // 显式握手，不靠首次调用的惰性初始化：这样 initialize 本身就是被验证的一步。
            McpSchema.InitializeResult initialization;
            try {
                initialization = client.initialize();
            } catch (RuntimeException handshakeFailure) {
                teardown(tomcat, server, transport, client, baseDir);
                throw new IllegalStateException("Streamable HTTP 握手失败；容器观测到的请求：" + valve.requests, handshakeFailure);
            }
            return new Fixture(tomcat, valve, server, transport, client, initialization, port);
        }

        private static Path createTempDir() {
            try {
                return Files.createTempDirectory("x07-mcp-streamable-http");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        McpSyncClient client() {
            return client;
        }

        McpSchema.InitializeResult initialization() {
            return initialization;
        }

        int port() {
            return port;
        }

        /** 由 SDK 传输自己宣告的协议版本，不在测试里另抄一份常量。 */
        List<String> supportedProtocolVersions() {
            return transport.protocolVersions();
        }

        /** 容器观测到的请求副本（Valve 内部是同步列表，这里再拷一份避免与关闭过程竞争）。 */
        List<RecordedRequest> observedRequests() {
            synchronized (valve.requests) {
                return List.copyOf(valve.requests);
            }
        }

        void close() {
            // 顺序有讲究：先让客户端发 DELETE（会话终止动作要被观测到），再停服务端与容器
            client.closeGracefully();
            teardown(tomcat, server, transport, null, null);
        }

        /** 再关一次：平台的会话关闭路径允许被重复调用，第二次不得抛异常。 */
        void closeAgain() {
            client.closeGracefully();
        }

        private static void teardown(
                Tomcat tomcat,
                McpSyncServer server,
                HttpClientStreamableHttpTransport transport,
                McpSyncClient client,
                Path baseDir) {
            if (client != null) {
                client.closeGracefully();
            }
            if (transport != null) {
                transport.closeGracefully().block(Duration.ofSeconds(10));
            }
            if (server != null) {
                server.closeGracefully();
            }
            if (tomcat != null) {
                stopContainer(tomcat);
            }
            deleteRecursively(baseDir);
        }

        /** 启动容器：{@code LifecycleException} 是检查异常，夹具里收敛成运行期异常。 */
        private static void startContainer(Tomcat tomcat) {
            try {
                tomcat.start();
            } catch (LifecycleException failure) {
                throw new IllegalStateException("嵌入式 Tomcat 启动失败", failure);
            }
        }

        /** 停止容器：停止失败不得掩盖正在断言的结论，但也不能静默——先记后抛。 */
        private static void stopContainer(Tomcat tomcat) {
            try {
                tomcat.stop();
                tomcat.destroy();
            } catch (LifecycleException failure) {
                throw new IllegalStateException("嵌入式 Tomcat 停止失败", failure);
            }
        }

        private static void deleteRecursively(Path root) {
            if (root == null || !Files.exists(root)) {
                return;
            }
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 临时目录删不掉不影响断言结论，不覆盖真实失败
                    }
                });
            } catch (IOException ignored) {
                // 同上：清理失败不得变成测试失败
            }
        }
    }
}
