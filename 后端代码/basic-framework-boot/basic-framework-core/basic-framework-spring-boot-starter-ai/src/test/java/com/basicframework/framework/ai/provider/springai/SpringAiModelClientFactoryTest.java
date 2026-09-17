package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 受管工厂与有界缓存（M02）：键身份、轮换失效、容量淘汰、关闭语义、策略校验。
 * 子类化工厂以避免真实厂商客户端（创建路径被覆盖为计数桩）。
 */
class SpringAiModelClientFactoryTest {

    private static final class RecordingFactory extends SpringAiModelClientFactory {

        private final AtomicInteger created = new AtomicInteger();

        private final List<SpringAiModelClient> closed = new ArrayList<>();

        RecordingFactory(AiHttpProperties properties, int maxClients) {
            super(properties, maxClients);
        }

        @Override
        protected SpringAiModelClient createClient(ModelEndpointSnapshot snapshot) {
            created.incrementAndGet();
            return new SpringAiModelClient(snapshot, null) {
                @Override
                public void close() {
                    closed.add(this);
                    super.close();
                }
            };
        }
    }

    private static AiHttpProperties properties() {
        AiHttpProperties properties = new AiHttpProperties();
        properties.setAllowedHosts(List.of("api.example.com"));
        properties.setAllowedPorts(List.of(443));
        return properties;
    }

    private static ModelEndpointSnapshot snapshot(int configRevision, int credentialRevision) {
        return new ModelEndpointSnapshot(
                9L,
                configRevision,
                credentialRevision,
                "openai_compatible",
                "https://api.example.com/v1",
                "gpt-4o-mini",
                Set.of(ModelCapability.TEXT),
                "sk-secret");
    }

    @Test
    void sameKeyReturnsSameClientAndRotationYieldsNewClient() {
        RecordingFactory factory = new RecordingFactory(properties(), 8);

        ModelPort first = factory.getOrCreate(snapshot(1, 1));
        ModelPort again = factory.getOrCreate(snapshot(1, 1));
        assertThat(again).isSameAs(first);
        assertThat(factory.size()).isEqualTo(1);

        // 凭据轮换：键里的 credentialRevision 变化 → 新请求得到新客户端（旧客户端不再被使用）
        ModelPort rotated = factory.getOrCreate(snapshot(1, 2));
        assertThat(rotated).isNotSameAs(first);
        assertThat(factory.created.get()).isEqualTo(2);
        // 释放旧客户端由显式失效完成（轮换/停用/地址迁移时调用），容量足够时不静默关闭
        factory.invalidate(9L);
        assertThat(factory.closed).hasSize(2);
        assertThat(factory.size()).isZero();
        // 失效后重新获取：按当前版本键重建
        ModelPort rebuilt = factory.getOrCreate(snapshot(1, 2));
        assertThat(factory.size()).isEqualTo(1);
        assertThat(rebuilt).isNotNull();

        // 配置版本变化同样得到新客户端
        ModelPort reconfigured = factory.getOrCreate(snapshot(2, 2));
        assertThat(reconfigured).isNotSameAs(rotated);
        // 失效后缓存只剩重建的 (1,2)，再加配置版本变化的 (2,2)
        assertThat(factory.size()).isEqualTo(2);
    }

    @Test
    void invalidateClosesEveryClientOfEndpointAndCloseClearsAll() {
        RecordingFactory factory = new RecordingFactory(properties(), 8);
        factory.getOrCreate(snapshot(1, 1));
        factory.getOrCreate(snapshot(2, 2));
        assertThat(factory.size()).isEqualTo(2);

        factory.invalidate(9L);
        assertThat(factory.size()).isZero();
        assertThat(factory.closed).hasSize(2);

        factory.getOrCreate(snapshot(3, 3));
        factory.close();
        assertThat(factory.size()).isZero();
    }

    @Test
    void boundedCacheEvictsAndClosesEldestClient() {
        RecordingFactory factory = new RecordingFactory(properties(), 2);
        factory.getOrCreate(snapshot(1, 1));
        factory.getOrCreate(snapshot(2, 2));
        factory.getOrCreate(snapshot(3, 3));

        assertThat(factory.size()).isEqualTo(2);
        assertThat(factory.closed).hasSize(1);
    }

    @Test
    void policyAndSnapshotValidationRejectsBeforeCreatingClient() {
        RecordingFactory factory = new RecordingFactory(properties(), 8);

        // 未配置凭据
        assertReason(
                factory,
                new ModelEndpointSnapshot(
                        9L,
                        1,
                        0,
                        "openai_compatible",
                        "https://api.example.com/v1",
                        "gpt-4o-mini",
                        Set.of(ModelCapability.TEXT),
                        null),
                ModelException.Reason.CREDENTIAL_UNAVAILABLE);
        // 不支持的提供方
        assertReason(
                factory,
                new ModelEndpointSnapshot(
                        9L,
                        1,
                        1,
                        "other_provider",
                        "https://api.example.com/v1",
                        "gpt-4o-mini",
                        Set.of(ModelCapability.TEXT),
                        "sk"),
                ModelException.Reason.CAPABILITY_UNSUPPORTED);
        // 主机不在允许清单
        assertReason(
                factory,
                new ModelEndpointSnapshot(
                        9L,
                        1,
                        1,
                        "openai_compatible",
                        "https://api.other.com/v1",
                        "gpt-4o-mini",
                        Set.of(ModelCapability.TEXT),
                        "sk"),
                ModelException.Reason.TARGET_NOT_ALLOWED);
        // 端口不在允许清单
        assertReason(
                factory,
                new ModelEndpointSnapshot(
                        9L,
                        1,
                        1,
                        "openai_compatible",
                        "https://api.example.com:8443/v1",
                        "gpt-4o-mini",
                        Set.of(ModelCapability.TEXT),
                        "sk"),
                ModelException.Reason.TARGET_NOT_ALLOWED);
        // 地址不合法
        assertReason(
                factory,
                new ModelEndpointSnapshot(
                        9L, 1, 1, "openai_compatible", "not a url", "gpt-4o-mini", Set.of(ModelCapability.TEXT), "sk"),
                ModelException.Reason.TARGET_NOT_ALLOWED);
        // 快照不完整
        assertReason(
                factory,
                new ModelEndpointSnapshot(
                        null,
                        1,
                        1,
                        "openai_compatible",
                        "https://api.example.com/v1",
                        "gpt-4o-mini",
                        Set.of(ModelCapability.TEXT),
                        "sk"),
                ModelException.Reason.ENDPOINT_NOT_FOUND);

        assertThat(factory.created.get()).isZero();
    }

    private static void assertReason(
            SpringAiModelClientFactory factory, ModelEndpointSnapshot snapshot, ModelException.Reason reason) {
        assertThatThrownBy(() -> factory.getOrCreate(snapshot))
                .isInstanceOf(ModelException.class)
                .satisfies(exception ->
                        assertThat(((ModelException) exception).getReason()).isEqualTo(reason));
    }

    @Test
    void clientRejectsAfterCloseAndWhenCapabilityMissing() throws Exception {
        AiHttpProperties properties = properties();
        RecordingFactory factory = new RecordingFactory(properties, 1);
        ModelPort client = factory.getOrCreate(snapshot(1, 1));

        // 未声明 TEXT 能力的端点拒绝文本生成
        ModelEndpointSnapshot embeddingOnly = new ModelEndpointSnapshot(
                10L,
                1,
                1,
                "openai_compatible",
                "https://api.example.com/v1",
                "text-embedding",
                Set.of(ModelCapability.EMBEDDING),
                "sk");
        ModelPort embeddingClient = factory.getOrCreate(embeddingOnly);
        assertThatThrownBy(() -> embeddingClient.generate(
                        com.basicframework.framework.ai.core.model.ModelRequest.of("text-embedding", "ping")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.CAPABILITY_UNSUPPORTED));
        assertThat(client.capabilities()).containsExactly(ModelCapability.TEXT);

        factory.close();
        assertThatThrownBy(() -> client.generate(
                        com.basicframework.framework.ai.core.model.ModelRequest.of("gpt-4o-mini", "ping")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.UPSTREAM_FAILED));
        assertThat(new CountDownLatch(0).await(1, TimeUnit.MILLISECONDS)).isTrue();
    }
}
