package com.basicframework.framework.ai.provider.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * 与真实 MCP 服务器的端到端握手（X07 逐步实施第 1 条的收尾项；对应证据文档"未验证项"第 1 条）。
 *
 * <p>对端是 <b>官方 SDK 自己的服务端实现</b>（{@link McpSyncServer} + {@link StdioServerTransportProvider}），
 * 以独立子进程运行；本侧是 {@link StdioClientTransport} 驱动的真实 {@link McpSyncClient}。
 * 两端同源同版本（mcp-core 2.0.1），因此跑的是一次真的 {@code initialize} 握手与真的
 * {@code tools/list} 往返，不是桩、不是 mock：握手由 SDK 的会话状态机驱动
 * （能力协商 → {@code notifications/initialized} → 请求/响应按 id 配对）。
 *
 * <p><b>为什么用 stdio 而不是 HTTP loopback</b>：证据文档上一轮失败的原因是手写 Streamable HTTP
 * 假服务端满足不了 SDK 对 {@code initialize} 之后 GET SSE 长连接的期望。本轮不再手写协议。
 * 但本仓库的测试 classpath 上<b>没有</b> {@code jakarta.servlet-api}（它在 mcp-core 里是
 * {@code provided} 作用域，不传递），也没有任何嵌入式 servlet 容器，所以 SDK 自带的
 * {@code HttpServletSseServerTransportProvider} / {@code HttpServletStreamableServerTransportProvider}
 * 这两个服务端传输在本模块<b>连编译都过不了</b>（实测：{@code 找不到 jakarta.servlet.http.HttpServlet 的类文件}）。
 * 补 HTTP 端到端需要改本模块 pom 增加 test 作用域依赖，超出 X07 §2.1 授权的两条依赖，故未做，
 * 仍列为未验证项（见证据文档 §10 第 1 条）。本类覆盖的是<b>协议层</b>握手，
 * 不覆盖 HTTP 传输层。
 *
 * <p>仍然刻意不碰 {@code callTool}：本卡只做发现，"发现"在类型上无法变成"执行"。
 */
class McpHandshakeE2ETest {

    /** 握手请求超时：子进程冷启动要拉起 JVM，留够余量但仍是上界。 */
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(60);

    /** 子进程专用日志配置：把日志赶到 stderr，否则会污染 stdio 上的 JSON-RPC 流。 */
    private static final String LOGBACK_STDIO_CONFIG = "mcp-stdio-logback.xml";

    @Test
    void initializeCompletesAgainstTheRealSdkServerAndReportsTheNegotiatedProtocol() {
        Fixture fixture = Fixture.start();

        McpSyncClient client = fixture.client();
        assertThat(client.isInitialized()).isTrue();

        McpSchema.InitializeResult initialization = fixture.initialization();
        assertThat(initialization).isNotNull();
        // 显式握手拿到的结果与客户端自己记录的一致：不是"恰好没报错"
        assertThat(client.getCurrentInitializationResult()).isEqualTo(initialization);
        // 协议版本必须是双方协商出的、非空的值：空串说明握手其实没走完。
        // 期望值写成字面量而不是抄服务端常量，否则改错服务端时测试会跟着一起错、永远绿。
        assertThat(initialization.protocolVersion()).isEqualTo("2025-11-25");
        assertThat(fixture.supportedProtocolVersions()).contains(initialization.protocolVersion());
        assertThat(initialization.serverInfo().name()).isEqualTo("x07-e2e-fixture-server");
        assertThat(initialization.serverInfo().version()).isEqualTo("1.0");
        assertThat(client.getServerInfo().name()).isEqualTo("x07-e2e-fixture-server");
        // 服务端必须真的宣告了 tools 能力，否则后面的 tools/list 不构成能力协商的证据
        assertThat(initialization.capabilities().tools()).isNotNull();

        fixture.close();
    }

    @Test
    void listToolsReturnsExactlyTheRegisteredToolWithAnUnchangedInputSchema() {
        Fixture fixture = Fixture.start();

        McpSchema.ListToolsResult result = fixture.client().listTools();

        assertThat(result).isNotNull();
        assertThat(result.tools()).hasSize(1);
        McpSchema.Tool tool = result.tools().get(0);
        assertThat(tool.name()).isEqualTo("mcp-search-orders");
        assertThat(tool.description()).isEqualTo("按地区查询订单数量（X07 端到端夹具）");
        // 入参 schema 逐字段核对：发现侧要靠它算指纹，任何增删或归一化都会让指纹失真。
        // 断言结构而不只是"与注册值相等"：相等是同义反复，这里钉住真实形状与内容。
        assertThat(tool.inputSchema()).containsEntry("type", "object").containsEntry("required", List.of("region"));
        assertThat(castMap(tool.inputSchema().get("properties")))
                .containsOnlyKeys("region", "limit")
                .containsEntry("region", Map.of("type", "string", "description", "地区编码"))
                .containsEntry("limit", Map.of("type", "integer", "description", "返回条数上限"));
        // schema 没被 SDK 偷偷塞进别的顶层键
        assertThat(tool.inputSchema()).containsOnlyKeys("type", "properties", "required");

        fixture.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void closingTheSessionLeavesNoLiveNonDaemonThreadBehind() {
        Set<Long> before = liveNonDaemonThreadIds();
        Fixture fixture = Fixture.start();

        assertThat(fixture.client().listTools().tools()).hasSize(1);
        fixture.close();

        // 关闭后新冒出来的非守护线程必须全部结束：SDK 传输的读取线程不能留在 surefire 进程里
        assertThat(liveNonDaemonThreadIds()).isSubsetOf(before);
        // 重复关闭必须幂等：平台的会话关闭路径允许被多次调用
        assertThatCode(fixture::closeAgain).doesNotThrowAnyException();
    }

    /** 当前存活的非守护线程 id（守护线程由 surefire 进程退出时统一回收，不纳入断言）。 */
    private static Set<Long> liveNonDaemonThreadIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && !thread.isDaemon()) {
                ids.add(thread.getId());
            }
        }
        return ids;
    }

    /** 一次"子进程服务端 + 进程内客户端"的组合，负责起停与资源回收。 */
    private static final class Fixture {

        private final StdioClientTransport transport;

        private final McpSyncClient client;

        private final McpSchema.InitializeResult initialization;

        private final List<String> serverStderr;

        private Fixture(
                StdioClientTransport transport,
                McpSyncClient client,
                McpSchema.InitializeResult initialization,
                List<String> serverStderr) {
            this.transport = transport;
            this.client = client;
            this.initialization = initialization;
            this.serverStderr = serverStderr;
        }

        static Fixture start() {
            McpJsonMapper mapper = new JacksonMcpJsonMapperSupplier().get();
            StdioClientTransport transport = new StdioClientTransport(
                    ServerParameters.builder(javaExecutable())
                            .args(
                                    "-Dlogback.configurationFile=" + LOGBACK_STDIO_CONFIG,
                                    "-cp",
                                    childClasspath(),
                                    FixtureServer.class.getName())
                            .build(),
                    mapper);
            // 子进程 stderr 收进列表：握手失败时要能看见服务端为什么没起来
            List<String> stderr = Collections.synchronizedList(new ArrayList<>());
            transport.setStdErrorHandler(stderr::add);
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(HANDSHAKE_TIMEOUT)
                    .clientInfo(new McpSchema.Implementation("x07-e2e-client", "1.0"))
                    .build();
            // 显式握手，不靠首次调用的惰性初始化：这样 initialize 本身就是被验证的一步。
            // 握手失败时把子进程 stderr 附在消息上，否则排查只能靠猜。
            McpSchema.InitializeResult initialization;
            try {
                initialization = client.initialize();
            } catch (RuntimeException handshakeFailure) {
                throw new IllegalStateException("MCP 握手失败；子进程 stderr：\n" + String.join("\n", stderr), handshakeFailure);
            }
            return new Fixture(transport, client, initialization, stderr);
        }

        McpSyncClient client() {
            return client;
        }

        McpSchema.InitializeResult initialization() {
            return initialization;
        }

        /** 由 SDK 传输自己宣告的协议版本，不在测试里另抄一份常量。 */
        List<String> supportedProtocolVersions() {
            return transport.protocolVersions();
        }

        void close() {
            client.closeGracefully();
            transport.closeGracefully().block(java.time.Duration.ofSeconds(10));
        }

        /** 再关一次：平台的会话关闭路径允许被重复调用，第二次不得抛异常。 */
        void closeAgain() {
            close();
        }

        /** 子进程 JVM 的 java 可执行文件（用当前运行的 java，避免依赖 PATH 里的版本）。 */
        private static String javaExecutable() {
            return Path.of(System.getProperty("java.home"), "bin", "java").toString();
        }

        /**
         * 子进程需要一份完整 classpath。
         *
         * <p>surefire 默认用 manifest-only jar 启动（{@code useManifestOnlyJar=true}），此时
         * {@code java.class.path} 只有一个引导 jar，真实依赖挂在它的 {@code Class-Path} 清单里；
         * 直接透传会让子进程 {@code NoClassDefFoundError}。所以这里把清单里的条目一并展开。
         */
        private static String childClasspath() {
            Set<String> entries = new LinkedHashSet<>();
            for (String declared : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                if (!declared.isBlank()) {
                    entries.add(declared);
                }
            }
            for (String entry : List.copyOf(entries)) {
                entries.addAll(manifestClassPathEntries(entry));
            }
            return String.join(File.pathSeparator, entries);
        }

        private static Set<String> manifestClassPathEntries(String classpathEntry) {
            if (!classpathEntry.endsWith(".jar") || !new File(classpathEntry).isFile()) {
                return Set.of();
            }
            try (JarFile jar = new JarFile(classpathEntry)) {
                Attributes attributes =
                        jar.getManifest() == null ? null : jar.getManifest().getMainAttributes();
                String declared = attributes == null ? null : attributes.getValue(Attributes.Name.CLASS_PATH);
                if (declared == null || declared.isBlank()) {
                    return Set.of();
                }
                // 清单里的相对 URI 相对于引导 jar 自身所在目录解析
                Path base = Path.of(classpathEntry).toAbsolutePath().getParent();
                Set<String> resolved = new LinkedHashSet<>();
                for (String reference : declared.split("\\s+")) {
                    if (reference.isBlank()) {
                        continue;
                    }
                    try {
                        resolved.add(base.resolve(Path.of(new java.net.URI(reference)))
                                .toString());
                    } catch (URISyntaxException | IllegalArgumentException malformed) {
                        // 引导 jar 清单里出现无法解析的条目时忽略该条目，其余照常透传
                    }
                }
                return resolved;
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }

    /**
     * 最小真实 MCP 服务器：官方 SDK 的 {@link McpSyncServer}，经 stdio 传输对外服务。
     *
     * <p>由子 JVM 以 {@code main} 启动，这是真实 MCP 服务器的运行形态（独立进程、标准输入输出）。
     */
    public static final class FixtureServer {

        static final String SERVER_NAME = "x07-e2e-fixture-server";

        static final String SERVER_VERSION = "1.0";

        static final String TOOL_NAME = "mcp-search-orders";

        static final String TOOL_DESCRIPTION = "按地区查询订单数量（X07 端到端夹具）";

        private static final Map<String, Object> TOOL_SCHEMA = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "region",
                        Map.of("type", "string", "description", "地区编码"),
                        "limit",
                        Map.of("type", "integer", "description", "返回条数上限")),
                "required",
                List.of("region"));

        private FixtureServer() {}

        public static void main(String[] args) {
            McpJsonMapper mapper = new JacksonMcpJsonMapperSupplier().get();
            StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper, System.in, System.out);
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(TOOL_NAME)
                    .description(TOOL_DESCRIPTION)
                    .inputSchema(TOOL_SCHEMA)
                    .build();
            McpServerFeatures.SyncToolSpecification specification = new McpServerFeatures.SyncToolSpecification(
                    tool,
                    (McpSyncServerExchange exchange, McpSchema.CallToolRequest request) ->
                            McpSchema.CallToolResult.builder()
                                    .addTextContent("x07-e2e-fixture")
                                    .isError(Boolean.FALSE)
                                    .build());
            McpSyncServer server = McpServer.sync(transport)
                    .serverInfo(SERVER_NAME, SERVER_VERSION)
                    .tools(specification)
                    .build();
            // 服务端常驻：stdin 由 SDK 的读取订阅持续消费，main 返回则进程退出
            try {
                new java.util.concurrent.CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                server.closeGracefully();
            }
        }
    }
}
