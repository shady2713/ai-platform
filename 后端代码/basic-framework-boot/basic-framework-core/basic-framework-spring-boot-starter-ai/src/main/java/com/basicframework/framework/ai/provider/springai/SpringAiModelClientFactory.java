package com.basicframework.framework.ai.provider.springai;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.GuardedExternalHttpClient;
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
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.retry.support.RetryTemplate;

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

    /** 受管客户端缓存容量上限（自动装配显式接线时也复用同一常量）。 */
    public static final int DEFAULT_MAX_CLIENTS = 32;

    private final AiHttpProperties httpProperties;

    private final AiModelProperties modelProperties;

    private final ExternalHttpClient externalHttpClient;

    /** 自建出站客户端时为 true：{@link #close()} 连带关闭它；外部注入的由 Bean 生命周期负责。 */
    private final boolean ownsExternalHttpClient;

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
        this(httpProperties, modelProperties, maxClients, new GuardedExternalHttpClient(httpProperties), true);
    }

    /**
     * 受管工厂主装配（M07）：出站边界由外部注入（构造器注入，不用字段注入）。
     *
     * <p>{@code externalHttpClient} 是 F09 的受控出站边界：工厂创建的全部 Spring AI 客户端
     * 以它为唯一传输，聊天/嵌入/转写/语音四条通道以及聊天流式通道的每一次出站请求都过守卫。
     */
    public SpringAiModelClientFactory(
            AiHttpProperties httpProperties,
            AiModelProperties modelProperties,
            int maxClients,
            ExternalHttpClient externalHttpClient,
            boolean ownsExternalHttpClient) {
        this.httpProperties = httpProperties;
        this.modelProperties = modelProperties;
        this.maxClients = maxClients;
        this.externalHttpClient = externalHttpClient;
        this.ownsExternalHttpClient = ownsExternalHttpClient;
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
     *
     * <p><b>M07：请求级出站治理。</b>聊天/嵌入/结构化/工具调用走 {@code OpenAiApi}，
     * 转写与语音合成走 {@code OpenAiAudioApi}；两个厂商客户端的阻塞传输（{@code RestClient}）
     * 与响应式传输（{@code WebClient}）都被换成 F09 受控边界适配器，因此**每一次实际发出的请求**
     * 都重新过守卫（允许清单、地址校验、协议、不跟随重定向、响应上限、超时），
     * 不再只依赖创建期的快照校验。
     *
     * <p>重试：四条通道一律显式传入**单次尝试**模板。厂商默认模板是 10 次指数退避，
     * 与平台受管重试（{@code AiModelProperties.maxAttempts}）叠加会放大上游压力；
     * 请求期被守卫拒绝时更不能重发（同一个不允许目标重试 10 次毫无意义，还会把拒绝变成几十秒的挂起）。
     * 重试次数与可重试原因只由平台策略决定。
     */
    protected SpringAiModelClient createClient(ModelEndpointSnapshot snapshot) {
        GuardedExternalHttpTransport transport = new GuardedExternalHttpTransport(externalHttpClient);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(snapshot.baseUrl())
                .apiKey(snapshot.apiKey())
                .restClientBuilder(transport.restClientBuilder())
                .webClientBuilder(transport.webClientBuilder())
                .build();
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(
                        OpenAiChatOptions.builder().model(snapshot.modelId()).build())
                .retryTemplate(singleAttemptRetryTemplate())
                .build();
        return new SpringAiModelClient(
                snapshot,
                chatModel,
                createEmbeddingModel(snapshot, openAiApi),
                createTranscriptionModel(snapshot, transport),
                createSpeechModel(snapshot, transport),
                modelProperties);
    }

    /** 只有声明了嵌入能力的端点才装配嵌入模型，避免为纯文本端点建立无用的调用通道。 */
    private EmbeddingModel createEmbeddingModel(ModelEndpointSnapshot snapshot, OpenAiApi openAiApi) {
        if (!snapshot.capabilities().contains(ModelCapability.EMBEDDING)) {
            return null;
        }
        return new OpenAiEmbeddingModel(
                openAiApi,
                MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder().model(snapshot.modelId()).build(),
                singleAttemptRetryTemplate());
    }

    /**
     * 只有声明了语音转写能力的端点才装配转写模型（非实时 STT）。
     *
     * <p>必须显式给 {@code responseFormat}：厂商模型的便捷构造器会自己填
     * {@code JSON}，而这里显式构造选项绕过了它，选项里为空时厂商在**发出任何请求之前**
     * 就抛 {@code response_format must not be null}（2026-10-01 实测，见证据文档的缺陷记录）。
     * 取 {@link OpenAiAudioApi.TranscriptResponseFormat#TEXT}：纯文本转写，探测与运行期一致。
     */
    private TranscriptionModel createTranscriptionModel(
            ModelEndpointSnapshot snapshot, GuardedExternalHttpTransport transport) {
        if (!snapshot.capabilities().contains(ModelCapability.SPEECH_TO_TEXT)) {
            return null;
        }
        return new OpenAiAudioTranscriptionModel(
                audioApi(snapshot, transport),
                OpenAiAudioTranscriptionOptions.builder()
                        .model(snapshot.modelId())
                        .responseFormat(OpenAiAudioApi.TranscriptResponseFormat.TEXT)
                        .build(),
                singleAttemptRetryTemplate());
    }

    /** 只有声明了语音合成能力的端点才装配合成模型（非实时 TTS）。 */
    private TextToSpeechModel createSpeechModel(
            ModelEndpointSnapshot snapshot, GuardedExternalHttpTransport transport) {
        if (!snapshot.capabilities().contains(ModelCapability.TEXT_TO_SPEECH)) {
            return null;
        }
        return new OpenAiAudioSpeechModel(
                audioApi(snapshot, transport),
                OpenAiAudioSpeechOptions.builder().model(snapshot.modelId()).build(),
                singleAttemptRetryTemplate());
    }

    /** 音频专用厂商客户端（与聊天客户端同一端点地址与凭据；传输同样换成受控边界）。 */
    private static OpenAiAudioApi audioApi(ModelEndpointSnapshot snapshot, GuardedExternalHttpTransport transport) {
        return OpenAiAudioApi.builder()
                .baseUrl(snapshot.baseUrl())
                .apiKey(snapshot.apiKey())
                .restClientBuilder(transport.restClientBuilder())
                .webClientBuilder(transport.webClientBuilder())
                .build();
    }

    /** 单次尝试的厂商重试模板：重试由平台受管层统一决定，厂商层不叠加重发。 */
    private static RetryTemplate singleAttemptRetryTemplate() {
        return RetryTemplate.builder().maxAttempts(1).build();
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
        if (ownsExternalHttpClient) {
            externalHttpClient.close();
        }
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
