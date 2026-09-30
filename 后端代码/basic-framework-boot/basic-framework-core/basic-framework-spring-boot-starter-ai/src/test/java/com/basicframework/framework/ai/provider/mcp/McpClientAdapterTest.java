package com.basicframework.framework.ai.provider.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * MCP 发现适配器：有界重试、明确终止、版本漂移默认拒绝、只发现不执行。
 *
 * <p>用确定性假传输而不是真网络：验收 3 要断言的是"试了几次、为什么停"，
 * 真实网络抖动无法稳定复现"恰好第 3 次失败"。真实 SDK 传输由
 * {@code McpSdkSessionFactoryIT} 在本地回环 HTTP 上单独验证。
 */
class McpClientAdapterTest {

    private static final String PROTOCOL = "2025-06-18";

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"region\":{\"type\":\"string\"}}}";

    private static McpServerEndpoint endpoint(int maxAttempts) {
        return new McpServerEndpoint("https://mcp.example.com", null, null, null, maxAttempts);
    }

    private static McpEndpointPolicy policy() {
        return new McpEndpointPolicy(Set.of("mcp.example.com"), Set.of(443), false);
    }

    private static McpToolDescriptor tool(String name) {
        return new McpToolDescriptor(name, name, "查询订单", SCHEMA);
    }

    /** 计数型假传输：每次尝试都按脚本抛错或返回清单。 */
    private static final class ScriptedFactory implements McpSessionFactory {

        private final AtomicInteger opens = new AtomicInteger();

        private final List<McpTermination> script;

        private ScriptedFactory(McpTermination... script) {
            this.script = List.of(script);
        }

        @Override
        public McpClientSession open(McpServerEndpoint endpoint, McpEndpointPolicy policy) {
            int index = opens.getAndIncrement();
            McpTermination failure = index < script.size() ? script.get(index) : null;
            return new McpClientSession() {
                @Override
                public String protocolVersion() {
                    return PROTOCOL;
                }

                @Override
                public String serverName() {
                    return "test-server";
                }

                @Override
                public List<McpToolDescriptor> listTools() {
                    if (failure != null) {
                        throw new McpClientException(failure, "scripted");
                    }
                    return List.of(tool("search_orders"));
                }

                @Override
                public void close() {}
            };
        }
    }

    @Test
    void successfulDiscoveryCarriesProtocolAndTools() {
        ScriptedFactory factory = new ScriptedFactory();
        McpDiscoverySnapshot snapshot =
                new McpClientAdapter(factory, Set.of(PROTOCOL), 50).discover(endpoint(3), policy());

        assertThat(snapshot.succeeded()).isTrue();
        assertThat(snapshot.protocolVersion()).isEqualTo(PROTOCOL);
        assertThat(snapshot.serverName()).isEqualTo("test-server");
        assertThat(snapshot.tools()).hasSize(1);
        assertThat(snapshot.attempts()).isEqualTo(1);
        assertThat(snapshot.termination()).isEqualTo(McpTermination.SUCCESS);
    }

    @Test
    void transientFailuresAreRetriedAndStopAtTheBound() {
        // 验收 3 正向：前两次瞬时失败、第三次成功 → 有界重试确实生效，且到第 3 次就停
        ScriptedFactory factory = new ScriptedFactory(McpTermination.TIMEOUT, McpTermination.UNREACHABLE);
        McpDiscoverySnapshot snapshot =
                new McpClientAdapter(factory, Set.of(PROTOCOL), 50).discover(endpoint(5), policy());

        assertThat(snapshot.succeeded()).isTrue();
        assertThat(snapshot.attempts()).isEqualTo(3);
        assertThat(factory.opens.get()).isEqualTo(3);
    }

    @Test
    void retryExhaustionTerminatesWithBoundedAttemptsAndNoSilentEmptyList() {
        // 验收 3 反向：永远超时 → 尝试次数等于上限，且**抛错**而不是返回空清单
        ScriptedFactory factory = new ScriptedFactory(
                McpTermination.TIMEOUT, McpTermination.TIMEOUT, McpTermination.TIMEOUT, McpTermination.TIMEOUT);
        McpClientAdapter adapter = new McpClientAdapter(factory, Set.of(PROTOCOL), 50);

        assertThatThrownBy(() -> adapter.discover(endpoint(3), policy()))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> {
                    McpClientException cause = (McpClientException) failure;
                    assertThat(cause.termination()).isEqualTo(McpTermination.TIMEOUT);
                    // 有界的直接证据：恰好试了上限次数，没有第 4 次
                    assertThat(cause.attempts()).isEqualTo(3);
                });
        assertThat(factory.opens.get()).isEqualTo(3);
    }

    @Test
    void nonRetryableFailuresStopOnTheFirstAttempt() {
        // 反向：授权被拒不重试（同样的令牌重试没有意义，只会放大对上游的压力）
        ScriptedFactory factory = new ScriptedFactory(McpTermination.UNAUTHORIZED, McpTermination.SUCCESS);
        McpClientAdapter adapter = new McpClientAdapter(factory, Set.of(PROTOCOL), 50);

        assertThatThrownBy(() -> adapter.discover(endpoint(5), policy()))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.UNAUTHORIZED));
        assertThat(factory.opens.get()).isEqualTo(1);
    }

    @Test
    void attemptCeilingIsClampedByTheEndpointType() {
        // 上界由类型保证而不是靠调用方自觉：声明 99 次也只夹到 5
        assertThat(new McpServerEndpoint("https://x", null, null, null, 99).maxAttempts())
                .isEqualTo(McpServerEndpoint.MAX_ATTEMPTS_CEILING);
        assertThat(new McpServerEndpoint("https://x", null, null, null, 0).maxAttempts())
                .isEqualTo(McpServerEndpoint.MIN_ATTEMPTS);
        assertThat(new McpServerEndpoint("https://x", null, null, null, -7).maxAttempts())
                .isEqualTo(McpServerEndpoint.MIN_ATTEMPTS);
    }

    @Test
    void protocolVersionDriftIsRejectedWithoutDowngrade() {
        // 验收 3/4 的版本面：不降级协商，直接拒绝
        McpSessionFactory drifting = (endpoint, policy) -> new McpClientSession() {
            @Override
            public String protocolVersion() {
                return "1999-01-01";
            }

            @Override
            public String serverName() {
                return "old-server";
            }

            @Override
            public List<McpToolDescriptor> listTools() {
                return List.of(tool("search_orders"));
            }

            @Override
            public void close() {}
        };
        assertThatThrownBy(() -> new McpClientAdapter(drifting, Set.of(PROTOCOL), 50).discover(endpoint(3), policy()))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.PROTOCOL_VERSION_UNSUPPORTED));
    }

    @Test
    void emptyProtocolAllowlistDeniesEverythingBeforeAnyConnection() {
        ScriptedFactory factory = new ScriptedFactory();
        assertThatThrownBy(() -> new McpClientAdapter(factory, Set.of(), 50).discover(endpoint(3), policy()))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.PROTOCOL_VERSION_UNSUPPORTED));
        assertThat(factory.opens.get()).isZero();
    }

    @Test
    void addressDenialHappensBeforeAnyConnection() {
        ScriptedFactory factory = new ScriptedFactory();
        McpServerEndpoint denied = new McpServerEndpoint("https://evil.example.org", null, null, null, 3);
        assertThatThrownBy(() -> new McpClientAdapter(factory, Set.of(PROTOCOL), 50).discover(denied, policy()))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.ADDRESS_DENIED));
        assertThat(factory.opens.get()).isZero();
    }

    @Test
    void nullEndpointOrPolicyIsDenied() {
        McpClientAdapter adapter = new McpClientAdapter(new ScriptedFactory(), Set.of(PROTOCOL), 50);
        assertThatThrownBy(() -> adapter.discover(null, policy())).isInstanceOf(McpClientException.class);
        assertThatThrownBy(() -> adapter.discover(endpoint(1), null)).isInstanceOf(McpClientException.class);
    }

    @Test
    void oversizedToolListIsRejectedRatherThanTruncated() {
        // 截断后的清单会被当成"上游就这些工具"，因此必须整体拒绝
        McpSessionFactory many = (endpoint, policy) -> new McpClientSession() {
            @Override
            public String protocolVersion() {
                return PROTOCOL;
            }

            @Override
            public String serverName() {
                return "big-server";
            }

            @Override
            public List<McpToolDescriptor> listTools() {
                return List.of(tool("a"), tool("b"), tool("c"));
            }

            @Override
            public void close() {}
        };
        assertThatThrownBy(() -> new McpClientAdapter(many, Set.of(PROTOCOL), 2).discover(endpoint(1), policy()))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.RESPONSE_TOO_LARGE));
    }

    @Test
    void nullToolListIsTreatedAsProtocolErrorNotAsEmptyCatalog() {
        McpSessionFactory nullTools = (endpoint, policy) -> new McpClientSession() {
            @Override
            public String protocolVersion() {
                return PROTOCOL;
            }

            @Override
            public String serverName() {
                return "null-server";
            }

            @Override
            public List<McpToolDescriptor> listTools() {
                return null;
            }

            @Override
            public void close() {}
        };
        assertThatThrownBy(() -> new McpClientAdapter(nullTools, Set.of(PROTOCOL), 50).discover(endpoint(1), policy()))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.PROTOCOL_ERROR));
    }

    @Test
    void unexpectedRuntimeFailureFromTransportCollapsesToStableTermination() {
        McpSessionFactory exploding = (endpoint, policy) -> {
            throw new IllegalStateException("上游返回了含内部路径的异常正文 /srv/secret");
        };
        assertThatThrownBy(() -> new McpClientAdapter(exploding, Set.of(PROTOCOL), 50).discover(endpoint(1), policy()))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> {
                    assertThat(((McpClientException) failure).termination()).isEqualTo(McpTermination.UNREACHABLE);
                    // 上游异常正文不得越过 provider 边界
                    assertThat(failure.getMessage()).doesNotContain("/srv/secret");
                });
    }

    @Test
    void sessionIsClosedEvenWhenListingFails() {
        AtomicInteger closed = new AtomicInteger();
        McpSessionFactory failing = (endpoint, policy) -> new McpClientSession() {
            @Override
            public String protocolVersion() {
                return PROTOCOL;
            }

            @Override
            public String serverName() {
                return "closing-server";
            }

            @Override
            public List<McpToolDescriptor> listTools() {
                throw new McpClientException(McpTermination.PROTOCOL_ERROR, "bad payload");
            }

            @Override
            public void close() {
                closed.incrementAndGet();
            }
        };
        assertThatThrownBy(() -> new McpClientAdapter(failing, Set.of(PROTOCOL), 50).discover(endpoint(1), policy()))
                .isInstanceOf(McpClientException.class);
        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    void emptySuccessfulToolListIsPreservedAsAnActualEmptyCatalog() {
        McpSessionFactory none = (endpoint, policy) -> new McpClientSession() {
            @Override
            public String protocolVersion() {
                return PROTOCOL;
            }

            @Override
            public String serverName() {
                return "empty-server";
            }

            @Override
            public List<McpToolDescriptor> listTools() {
                return List.of();
            }

            @Override
            public void close() {}
        };
        McpDiscoverySnapshot snapshot =
                new McpClientAdapter(none, Set.of(PROTOCOL), 50).discover(endpoint(1), policy());
        // 成功且真的没有工具，与"连不上"必须是两种不同的结论
        assertThat(snapshot.succeeded()).isTrue();
        assertThat(snapshot.tools()).isEmpty();
    }

    @Test
    void constructorClampsNonPositiveToolCeiling() {
        // maxTools 传 0/负数时夹到 1：单工具清单仍能通过（夹紧的是上限，不是"全部拒绝"）
        assertThat(new McpClientAdapter(new ScriptedFactory(), Set.of(PROTOCOL), 0)
                        .discover(endpoint(1), policy())
                        .succeeded())
                .isTrue();
        assertThat(new McpClientAdapter(new ScriptedFactory(), Set.of(PROTOCOL), -5)
                        .discover(endpoint(1), policy())
                        .succeeded())
                .isTrue();
    }

    @Test
    void nullProtocolAllowlistCollapsesToDenyAll() {
        // 构造器归一：null 允许清单 = 没有任何版本被批准 = 拒绝，而不是"不校验"
        ScriptedFactory factory = new ScriptedFactory();
        assertThatThrownBy(() -> new McpClientAdapter(factory, null, 50).discover(endpoint(1), policy()))
                .isInstanceOf(McpClientException.class)
                .satisfies(failure -> assertThat(((McpClientException) failure).termination())
                        .isEqualTo(McpTermination.PROTOCOL_VERSION_UNSUPPORTED));
        assertThat(factory.opens.get()).isZero();
    }
}
