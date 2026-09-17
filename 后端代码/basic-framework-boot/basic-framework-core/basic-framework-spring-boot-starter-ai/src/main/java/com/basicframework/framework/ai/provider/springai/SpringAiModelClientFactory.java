package com.basicframework.framework.ai.provider.springai;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelClientFactory;
import com.basicframework.framework.ai.core.model.ModelEndpointKey;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 受管模型客户端工厂（M02）：按端点快照创建 Spring AI 客户端，并按
 * {@code (endpointId, configRevision, credentialRevision)} 有界缓存。
 *
 * <p>规则：
 * <ul>
 *   <li>创建前校验提供方标识与目标地址是否落在出站策略允许清单内（与 F09 边界同一策略来源）；</li>
 *   <li>同一键返回同一实例；键变化（改配置或轮换凭据）得到新客户端，旧客户端失效并不再接受新请求；</li>
 *   <li>{@link #invalidate(Long)} 关闭某端点全部客户端；{@link #close()} 关闭全部；</li>
 *   <li>凭据只在受控内存中传递，不进入缓存键、toString、日志。</li>
 * </ul>
 */
public class SpringAiModelClientFactory implements ModelClientFactory {

    /** 支持的提供方标识。 */
    public static final String PROVIDER_OPENAI_COMPATIBLE = "openai_compatible";

    private static final int DEFAULT_MAX_CLIENTS = 32;

    private final AiHttpProperties httpProperties;

    private final AiModelProperties modelProperties;

    private final int maxClients;

    private final Map<ModelEndpointKey, SpringAiModelClient> clients;

    public SpringAiModelClientFactory(AiHttpProperties httpProperties) {
        this(httpProperties, new AiModelProperties(), DEFAULT_MAX_CLIENTS);
    }

    public SpringAiModelClientFactory(AiHttpProperties httpProperties, int maxClients) {
        this(httpProperties, new AiModelProperties(), maxClients);
    }

    public SpringAiModelClientFactory(AiHttpProperties httpProperties, AiModelProperties modelProperties) {
        this(httpProperties, modelProperties, DEFAULT_MAX_CLIENTS);
    }

    public SpringAiModelClientFactory(
            AiHttpProperties httpProperties, AiModelProperties modelProperties, int maxClients) {
        this.httpProperties = httpProperties;
        this.modelProperties = modelProperties;
        this.maxClients = maxClients;
        this.clients = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ModelEndpointKey, SpringAiModelClient> eldest) {
                if (size() > SpringAiModelClientFactory.this.maxClients) {
                    eldest.getValue().close();
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    public synchronized ModelPort getOrCreate(ModelEndpointSnapshot snapshot) {
        validateSnapshot(snapshot);
        SpringAiModelClient existing = clients.get(snapshot.key());
        if (existing != null) {
            return existing;
        }
        SpringAiModelClient created = createClient(snapshot);
        clients.put(snapshot.key(), created);
        return created;
    }

    /**
     * 创建厂商客户端；子类可在测试或未来提供方扩展中覆盖。
     */
    protected SpringAiModelClient createClient(ModelEndpointSnapshot snapshot) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(snapshot.baseUrl())
                .apiKey(snapshot.apiKey())
                .build();
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(
                        OpenAiChatOptions.builder().model(snapshot.modelId()).build())
                .build();
        return new SpringAiModelClient(snapshot, chatModel, createEmbeddingModel(snapshot, openAiApi), modelProperties);
    }

    /** 只有声明了嵌入能力的端点才装配嵌入模型，避免为纯文本端点建立无用的调用通道。 */
    private EmbeddingModel createEmbeddingModel(ModelEndpointSnapshot snapshot, OpenAiApi openAiApi) {
        if (!snapshot.capabilities().contains(ModelCapability.EMBEDDING)) {
            return null;
        }
        return new OpenAiEmbeddingModel(
                openAiApi,
                MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder().model(snapshot.modelId()).build());
    }

    @Override
    public synchronized void invalidate(Long endpointId) {
        List<ModelEndpointKey> keys = new ArrayList<>();
        for (ModelEndpointKey key : clients.keySet()) {
            if (key.endpointId().equals(endpointId)) {
                keys.add(key);
            }
        }
        for (ModelEndpointKey key : keys) {
            SpringAiModelClient removed = clients.remove(key);
            if (removed != null) {
                removed.close();
            }
        }
    }

    @Override
    public synchronized void close() {
        clients.values().forEach(SpringAiModelClient::close);
        clients.clear();
    }

    /** 当前缓存的客户端数量（用于运维观测与测试断言）。 */
    public synchronized int size() {
        return clients.size();
    }

    private void validateSnapshot(ModelEndpointSnapshot snapshot) {
        if (snapshot == null
                || snapshot.endpointId() == null
                || snapshot.credentialRevision() == null
                || snapshot.configRevision() == null) {
            throw new ModelException(ModelException.Reason.ENDPOINT_NOT_FOUND, "端点快照不完整");
        }
        if (snapshot.provider() == null
                || !PROVIDER_OPENAI_COMPATIBLE.equals(snapshot.provider().toLowerCase(Locale.ROOT))) {
            throw new ModelException(ModelException.Reason.CAPABILITY_UNSUPPORTED, "暂不支持的模型提供方");
        }
        if (!snapshot.credentialConfigured()) {
            throw new ModelException(ModelException.Reason.CREDENTIAL_UNAVAILABLE, "端点未配置凭据");
        }
        URI uri;
        try {
            uri = new URI(snapshot.baseUrl());
        } catch (URISyntaxException exception) {
            throw new ModelException(ModelException.Reason.TARGET_NOT_ALLOWED, "端点地址不合法", exception);
        }
        if (uri.getHost() == null || !httpProperties.getAllowedHosts().contains(uri.getHost())) {
            throw new ModelException(ModelException.Reason.TARGET_NOT_ALLOWED, "端点主机不在出站允许清单内");
        }
        int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        if (!httpProperties.getAllowedPorts().contains(port)) {
            throw new ModelException(ModelException.Reason.TARGET_NOT_ALLOWED, "端点端口不在出站允许清单内");
        }
    }
}
