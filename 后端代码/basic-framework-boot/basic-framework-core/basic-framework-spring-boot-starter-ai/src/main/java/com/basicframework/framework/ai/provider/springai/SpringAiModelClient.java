package com.basicframework.framework.ai.provider.springai;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.EmbeddingResponse;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelEvent;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelProbeKind;
import com.basicframework.framework.ai.core.model.ModelProbeResult;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelStream;
import com.basicframework.framework.ai.core.model.ModelToolCall;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.StructuredJsonOutput;
import com.basicframework.framework.ai.core.model.StructuredModelRequest;
import com.basicframework.framework.ai.core.model.StructuredModelResult;
import com.basicframework.framework.ai.core.model.media.MediaArtifact;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisRequest;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.ai.audio.transcription.AudioTranscription;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.Speech;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

/**
 * Spring AI 受管客户端（M02 建立，M03 增加文本流与结构化输出，M04 增加批量嵌入与能力探测）：本包是全仓库唯一引用
 * Spring AI 类型的区域。
 *
 * <p>职责：把自有请求转成厂商调用、把厂商输出转回自有响应/事件、把失败收敛为稳定错误，
 * 并落实输出大小上限、有界重试、流式空闲超时与嵌入批次上限。凭据只存在于构造时的受控内存中；
 * 客户端关闭后拒绝新请求。工具调用只作为数据向外暴露，本类不执行任何工具。
 */
public class SpringAiModelClient implements ModelPort {

    /** 结构化输出的提示词外壳：把 Schema 作为输出契约交给模型，平台侧再校验。 */
    private static final String STRUCTURED_INSTRUCTION =
            """
            请只输出一个 JSON 对象，不要输出解释文字或 Markdown 代码块。
            输出必须满足以下 JSON Schema：
            """;

    /** 探测用最小提示词与 Schema：只验证能力可用，不承载业务语义。 */
    private static final String PROBE_PROMPT = "ping";

    private static final String PROBE_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"ok\"],\"properties\":{\"ok\":{\"type\":\"boolean\"}}}";

    private static final String PROBE_TOOL_NAME = "platform_probe";

    private static final String PROBE_TOOL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}";

    private static final String PROBE_TOOL_PROMPT = "请调用 platform_probe 工具，参数 value=ping。只在需要调用工具时返回工具调用，不要返回解释文字。";

    /** 图片理解探测提示词：只要求一句描述，避免把探测变成一次长输出。 */
    private static final String PROBE_IMAGE_PROMPT = "用一句话描述这张图片的主要内容。";

    /** OCR 探测提示词：要求提取文字；识别不到也要回复（空回复按"未返回文本"处理）。 */
    private static final String PROBE_OCR_PROMPT = "提取图片中的文字，只输出文字内容。";

    /** TTS 探测文本：最短的可合成输入，不承载业务语义。 */
    private static final String PROBE_SPEECH_TEXT = "ping";

    /** 转写探测夹具在 multipart 里的文件名（扩展名与合成 WAV 夹具一致）。 */
    private static final String PROBE_AUDIO_FILE_NAME = "platform-probe.wav";

    /** 探测文本对应的输出格式：mp3 是 TTS 端点最通用的响应格式。 */
    private static final String PROBE_SPEECH_FORMAT = "mp3";

    /** 语音输出格式映射表（平台词汇 → 厂商响应格式 + 产物 MIME）。 */
    private static final Map<String, SpeechFormat> SPEECH_FORMATS = Map.of(
            "mp3", new SpeechFormat(OpenAiAudioApi.SpeechRequest.AudioResponseFormat.MP3, "audio/mpeg"),
            "wav", new SpeechFormat(OpenAiAudioApi.SpeechRequest.AudioResponseFormat.WAV, "audio/wav"),
            // 平台契约里的 opus 以 Ogg 封装返回（audio/ogg），对应厂商的 opus 响应格式
            "opus", new SpeechFormat(OpenAiAudioApi.SpeechRequest.AudioResponseFormat.OPUS, "audio/ogg"));

    private final ModelEndpointSnapshot snapshot;

    private final ChatModel chatModel;

    /** 仅在端点声明 EMBEDDING 时装配；其余情况为 null，嵌入调用按能力缺失拒绝。 */
    private final EmbeddingModel embeddingModel;

    /** 仅在端点声明 SPEECH_TO_TEXT 时装配；其余情况为 null，转写调用按能力缺失拒绝（X04）。 */
    private final TranscriptionModel transcriptionModel;

    /** 仅在端点声明 TEXT_TO_SPEECH 时装配；其余情况为 null，合成调用按能力缺失拒绝（X04）。 */
    private final TextToSpeechModel textToSpeechModel;

    private final Set<ModelCapability> capabilities;

    private final AiModelProperties properties;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SpringAiModelClient(ModelEndpointSnapshot snapshot, ChatModel chatModel) {
        this(snapshot, chatModel, null, new AiModelProperties());
    }

    public SpringAiModelClient(ModelEndpointSnapshot snapshot, ChatModel chatModel, AiModelProperties properties) {
        this(snapshot, chatModel, null, properties);
    }

    public SpringAiModelClient(
            ModelEndpointSnapshot snapshot,
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            AiModelProperties properties) {
        this(snapshot, chatModel, embeddingModel, null, null, properties);
    }

    /**
     * 受管客户端完整装配（X04 追加语音通道）：语音模型同样**只在端点声明对应能力时**由工厂装配，
     * 未装配时媒体调用按能力缺失拒绝，不做静默降级。
     */
    public SpringAiModelClient(
            ModelEndpointSnapshot snapshot,
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            TranscriptionModel transcriptionModel,
            TextToSpeechModel textToSpeechModel,
            AiModelProperties properties) {
        this.snapshot = snapshot;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.transcriptionModel = transcriptionModel;
        this.textToSpeechModel = textToSpeechModel;
        this.capabilities = Set.copyOf(snapshot.capabilities());
        this.properties = properties;
    }

    @Override
    public Set<ModelCapability> capabilities() {
        return capabilities;
    }

    @Override
    public ModelResponse generate(ModelRequest request) {
        requireOpen();
        requireCapability(ModelCapability.TEXT);
        return withRetry(() -> callOnce(request));
    }

    @Override
    public ModelStream stream(ModelRequest request) {
        requireOpen();
        requireCapability(ModelCapability.TEXT_STREAM);
        String prompt = request.prompt() == null ? "" : request.prompt();
        try {
            Flux<ChatResponse> responses = chatModel.stream(new Prompt(new UserMessage(prompt)));
            return new SpringAiModelStream(responses, properties, snapshot.modelId(), request.timeout());
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
    }

    @Override
    public StructuredModelResult generateStructured(StructuredModelRequest request) {
        requireOpen();
        requireCapability(ModelCapability.STRUCTURED_OUTPUT);
        StructuredJsonOutput.validateRequestSchema(request.jsonSchema());
        ModelRequest promptRequest =
                new ModelRequest(request.modelId(), buildStructuredPrompt(request), request.timeout());
        ModelResponse response = withRetry(() -> callOnce(promptRequest));
        JsonNode value = StructuredJsonOutput.parseObject(response.text(), properties.getMaxRepairSteps());
        return new StructuredModelResult(
                StructuredJsonOutput.compact(value),
                value,
                response.usage(),
                response.finishReason(),
                snapshot.modelId());
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        requireOpen();
        requireCapability(ModelCapability.EMBEDDING);
        if (embeddingModel == null) {
            throw new ModelException(
                    ModelException.Reason.CAPABILITY_UNSUPPORTED, "端点未装配嵌入模型：" + ModelCapability.EMBEDDING);
        }
        if (request.batchSize() > properties.getMaxEmbeddingBatch()) {
            throw new ModelException(
                    ModelException.Reason.BATCH_TOO_LARGE, "嵌入批次超过上限（" + properties.getMaxEmbeddingBatch() + " 条）");
        }
        return withRetry(() -> embedOnce(request));
    }

    private EmbeddingResponse embedOnce(EmbeddingRequest request) {
        try {
            org.springframework.ai.embedding.EmbeddingResponse vendorResponse =
                    embeddingModel.embedForResponse(request.texts());
            return toEmbeddingResponse(vendorResponse, request.batchSize());
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
    }

    private EmbeddingResponse toEmbeddingResponse(
            org.springframework.ai.embedding.EmbeddingResponse vendorResponse, int expectedSize) {
        List<org.springframework.ai.embedding.Embedding> results =
                vendorResponse == null ? null : vendorResponse.getResults();
        if (results == null || results.size() != expectedSize) {
            throw new ModelException(ModelException.Reason.UPSTREAM_FAILED, "嵌入响应条数与请求不一致");
        }
        List<org.springframework.ai.embedding.Embedding> ordered = new ArrayList<>(results);
        ordered.sort(Comparator.comparing(embedding -> embedding.getIndex() == null ? 0 : embedding.getIndex()));
        List<float[]> vectors = new ArrayList<>(ordered.size());
        for (var embedding : ordered) {
            vectors.add(embedding.getOutput());
        }
        ModelUsage usage = vendorResponse.getMetadata() == null
                ? ModelUsage.UNKNOWN
                : toUsage(vendorResponse.getMetadata().getUsage());
        return new EmbeddingResponse(vectors, usage, snapshot.modelId());
    }

    @Override
    public ModelProbeResult probe(ModelProbeKind kind) {
        if (closed.get()) {
            return ModelProbeResult.failed(kind, ModelException.Reason.AI_DISABLED, 0L);
        }
        long startedAt = System.nanoTime();
        try {
            return switch (kind) {
                case CONNECTIVITY -> probeConnectivity(startedAt);
                case TEXT -> probeText(startedAt);
                case TEXT_STREAM -> probeTextStream(startedAt);
                case STRUCTURED_OUTPUT -> probeStructuredOutput(startedAt);
                case TOOL_CALLING -> probeToolCalling(startedAt);
                case EMBEDDING -> probeEmbedding(startedAt);
                case IMAGE_UNDERSTANDING -> probeImageUnderstanding(startedAt);
                case IMAGE_OCR -> probeImageOcr(startedAt);
                case SPEECH_TO_TEXT -> probeSpeechToText(startedAt);
                case TEXT_TO_SPEECH -> probeTextToSpeech(startedAt);
                // 图片生成/编辑属 X03 的可选扩展：本适配器未实现时返回"适配器未实现"的稳定结论，
                // 不发起任何厂商调用（因此不会产生隐藏外发），也不回退为文本/同类媒体探测。
                case IMAGE_GENERATION, IMAGE_EDIT ->
                    ModelProbeResult.unsupported(kind, ModelProbeResult.CODE_ADAPTER_NOT_IMPLEMENTED);
            };
        } catch (ModelException exception) {
            if (exception.getReason() == ModelException.Reason.CAPABILITY_UNSUPPORTED) {
                return ModelProbeResult.unsupported(kind, ModelProbeResult.CODE_CAPABILITY_NOT_DECLARED);
            }
            return ModelProbeResult.failed(kind, exception.getReason(), elapsedMillis(startedAt));
        } catch (RuntimeException exception) {
            return ModelProbeResult.failed(kind, ModelException.Reason.UPSTREAM_FAILED, elapsedMillis(startedAt));
        }
    }

    private ModelProbeResult probeConnectivity(long startedAt) {
        callOnce(ModelRequest.of(snapshot.modelId(), PROBE_PROMPT));
        return ModelProbeResult.supported(ModelProbeKind.CONNECTIVITY, null, elapsedMillis(startedAt));
    }

    private ModelProbeResult probeText(long startedAt) {
        ModelResponse response = callOnce(ModelRequest.of(snapshot.modelId(), PROBE_PROMPT));
        if (response.text() == null || response.text().isBlank()) {
            return ModelProbeResult.unsupported(ModelProbeKind.TEXT, ModelProbeResult.CODE_NO_TEXT_RETURNED);
        }
        return ModelProbeResult.supported(ModelProbeKind.TEXT, null, elapsedMillis(startedAt));
    }

    /** 流式探测：至少收到一个文本增量并正常结束；失败按稳定原因记录，不静默降级。 */
    private ModelProbeResult probeTextStream(long startedAt) {
        int deltas = 0;
        try (ModelStream stream = stream(ModelRequest.of(snapshot.modelId(), PROBE_PROMPT))) {
            while (stream.hasNext()) {
                ModelEvent event = stream.next();
                if (event.type() == ModelEvent.Type.DELTA) {
                    deltas++;
                }
            }
        }
        if (deltas == 0) {
            return ModelProbeResult.unsupported(ModelProbeKind.TEXT_STREAM, ModelProbeResult.CODE_NO_TEXT_RETURNED);
        }
        return ModelProbeResult.supported(ModelProbeKind.TEXT_STREAM, null, elapsedMillis(startedAt));
    }

    private ModelProbeResult probeStructuredOutput(long startedAt) {
        generateStructured(new StructuredModelRequest(snapshot.modelId(), PROBE_PROMPT, PROBE_SCHEMA, null));
        return ModelProbeResult.supported(ModelProbeKind.STRUCTURED_OUTPUT, null, elapsedMillis(startedAt));
    }

    /**
     * 工具调用探测：注册一个**禁止执行**的占位工具并关闭厂商内部工具执行循环，
     * 只检查模型是否返回工具调用请求；一旦占位工具被执行即判定失败，杜绝自动执行。
     */
    private ModelProbeResult probeToolCalling(long startedAt) {
        requireCapability(ModelCapability.TOOL_CALLING);
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback probeTool = new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(PROBE_TOOL_NAME)
                        .description("平台能力探测占位工具，禁止执行")
                        .inputSchema(PROBE_TOOL_SCHEMA)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                executed.set(true);
                throw new IllegalStateException("探测占位工具不允许被执行");
            }
        };
        ChatResponse response;
        try {
            ChatOptions options = OpenAiChatOptions.builder()
                    .toolCallbacks(List.of(probeTool))
                    .internalToolExecutionEnabled(false)
                    .build();
            response = chatModel.call(new Prompt(new UserMessage(PROBE_TOOL_PROMPT), options));
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
        if (executed.get()) {
            throw new ModelException(ModelException.Reason.UPSTREAM_FAILED, "探测占位工具被自动执行");
        }
        if (response == null || !response.hasToolCalls()) {
            return ModelProbeResult.unsupported(
                    ModelProbeKind.TOOL_CALLING, ModelProbeResult.CODE_TOOL_CALL_NOT_RETURNED);
        }
        return ModelProbeResult.supported(ModelProbeKind.TOOL_CALLING, null, elapsedMillis(startedAt));
    }

    private ModelProbeResult probeEmbedding(long startedAt) {
        EmbeddingResponse response = embed(new EmbeddingRequest(snapshot.modelId(), List.of(PROBE_PROMPT), null));
        return ModelProbeResult.supported(ModelProbeKind.EMBEDDING, response.dimensions(), elapsedMillis(startedAt));
    }

    /**
     * 图片理解探测（X02）：用平台内置的最小合成图做一次**真实多模态调用**，只判"是否返回非空文本"。
     *
     * <p>不校验描述内容的语义正确性：探测要回答的是"这个端点收不收图片、返不返文本"，
     * 效果评测属于模型评测流程，不能拿一次探测代替。
     */
    private ModelProbeResult probeImageUnderstanding(long startedAt) {
        requireCapability(ModelCapability.IMAGE_UNDERSTANDING);
        String text = callVisionOnce(SyntheticMediaFixtures.understandingFixturePng(), PROBE_IMAGE_PROMPT);
        if (text.isBlank()) {
            return ModelProbeResult.unsupported(
                    ModelProbeKind.IMAGE_UNDERSTANDING, ModelProbeResult.CODE_NO_TEXT_RETURNED);
        }
        return ModelProbeResult.supported(ModelProbeKind.IMAGE_UNDERSTANDING, null, elapsedMillis(startedAt));
    }

    /**
     * OCR 探测（X02）：合成"文字行条带"图真实调用一次，按冻结语义只要求返回非空文本
     * （{@code ModelProbeKind.IMAGE_OCR} 明确不要求逐字匹配，避免字体差异导致误判）。
     */
    private ModelProbeResult probeImageOcr(long startedAt) {
        requireCapability(ModelCapability.IMAGE_OCR);
        String text = callVisionOnce(SyntheticMediaFixtures.ocrFixturePng(), PROBE_OCR_PROMPT);
        if (text.isBlank()) {
            return ModelProbeResult.unsupported(ModelProbeKind.IMAGE_OCR, ModelProbeResult.CODE_NO_TEXT_RETURNED);
        }
        return ModelProbeResult.supported(ModelProbeKind.IMAGE_OCR, null, elapsedMillis(startedAt));
    }

    /**
     * 媒体探测的最小多模态调用：一张平台合成图 + 一行提示词；失败收敛为稳定原因，不回传厂商报文。
     */
    private String callVisionOnce(byte[] imageBytes, String prompt) {
        try {
            Media media = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes));
            UserMessage message =
                    UserMessage.builder().text(prompt).media(media).build();
            ChatResponse response = chatModel.call(new Prompt(message));
            if (response == null
                    || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                return "";
            }
            String text = response.getResult().getOutput().getText();
            return text == null ? "" : text;
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
    }

    /**
     * 语音转写探测（X04）：用平台内置的最小合成 WAV 夹具做一次**真实转写调用**，只判"是否返回非空文本"。
     *
     * <p>与 OCR 同一套诚实语义：不校验识别准确率（合成夹具本来就不保证能被识别成文字），
     * 端点对非语音内容返回空文本时结论记 {@code UNSUPPORTED + NO_TEXT_RETURNED}，
     * 不因此推断端点不可用、也不改用文本能力凑结论。厂商协议细节只在 provider 包内，
     * 探测结论只记录状态/明细码/耗时，不落输入内容与上游报文。
     */
    private ModelProbeResult probeSpeechToText(long startedAt) {
        requireCapability(ModelCapability.SPEECH_TO_TEXT);
        if (transcriptionModel == null) {
            // 端点声明了能力但适配器没有装配转写通道：只能给出"适配器未实现"的稳定结论
            return ModelProbeResult.unsupported(
                    ModelProbeKind.SPEECH_TO_TEXT, ModelProbeResult.CODE_ADAPTER_NOT_IMPLEMENTED);
        }
        String text = transcribeProbe(SyntheticMediaFixtures.speechFixtureWav());
        if (text == null || text.isBlank()) {
            return ModelProbeResult.unsupported(ModelProbeKind.SPEECH_TO_TEXT, ModelProbeResult.CODE_NO_TEXT_RETURNED);
        }
        return ModelProbeResult.supported(ModelProbeKind.SPEECH_TO_TEXT, null, elapsedMillis(startedAt));
    }

    /**
     * 语音合成探测（X04）：用最短文本做一次**真实合成调用**，只判"是否返回非空音频字节"。
     */
    private ModelProbeResult probeTextToSpeech(long startedAt) {
        requireCapability(ModelCapability.TEXT_TO_SPEECH);
        if (textToSpeechModel == null) {
            return ModelProbeResult.unsupported(
                    ModelProbeKind.TEXT_TO_SPEECH, ModelProbeResult.CODE_ADAPTER_NOT_IMPLEMENTED);
        }
        byte[] audio = callSpeech(PROBE_SPEECH_TEXT, null, PROBE_SPEECH_FORMAT);
        if (audio == null || audio.length == 0) {
            return ModelProbeResult.unsupported(ModelProbeKind.TEXT_TO_SPEECH, ModelProbeResult.CODE_NO_AUDIO_RETURNED);
        }
        return ModelProbeResult.supported(ModelProbeKind.TEXT_TO_SPEECH, null, elapsedMillis(startedAt));
    }

    /**
     * 语音合成运行期调用（X04）：文本 → 音频字节；空产物按 {@code MEDIA_OUTPUT_EMPTY} 拒绝。
     *
     * <p>用量如实：厂商 TTS 响应不携带平台可用的计量，记 {@link ModelUsage#UNKNOWN}（不写 0），
     * 音频时长也按未知留空（本适配器不做音频解码），不伪造上游计数。
     *
     * <p>请求里的超时尚未映射到厂商音频模型（与文本/嵌入路径同一现状），
     * 平台级超时仍由端点 HTTP 客户端配置生效，不在此处假装已支持。
     */
    @Override
    public SpeechSynthesisResponse synthesizeSpeech(SpeechSynthesisRequest request) {
        requireOpen();
        requireCapability(ModelCapability.TEXT_TO_SPEECH);
        if (textToSpeechModel == null) {
            throw new ModelException(
                    ModelException.Reason.CAPABILITY_UNSUPPORTED, "端点未装配语音合成模型：" + ModelCapability.TEXT_TO_SPEECH);
        }
        byte[] audio = withRetry(() -> callSpeech(request.text(), request.voice(), request.outputFormat()));
        if (audio == null || audio.length == 0) {
            throw new ModelException(ModelException.Reason.MEDIA_OUTPUT_EMPTY, "上游未返回音频产物");
        }
        SpeechFormat format = speechFormatOf(request.outputFormat());
        return new SpeechSynthesisResponse(
                new MediaArtifact(format.mimeType(), audio, null, null, null, null),
                ModelUsage.UNKNOWN,
                snapshot.modelId());
    }

    /** 一次厂商转写调用（探测与运行期共用）：失败收敛为稳定原因，不回传厂商报文。 */
    private String transcribeProbe(byte[] audioBytes) {
        try {
            AudioTranscriptionResponse response =
                    transcriptionModel.call(new AudioTranscriptionPrompt(new ProbeAudioResource(audioBytes)));
            AudioTranscription result = response == null ? null : response.getResult();
            return result == null ? null : result.getOutput();
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
    }

    /** 一次厂商合成调用（探测与运行期共用）：失败收敛为稳定原因，不回传厂商报文。 */
    private byte[] callSpeech(String text, String voice, String outputFormat) {
        OpenAiAudioSpeechOptions.Builder options = OpenAiAudioSpeechOptions.builder()
                .model(snapshot.modelId())
                .input(text)
                .responseFormat(speechFormatOf(outputFormat).vendorFormat());
        if (voice != null) {
            // 请求 record 已把空白音色归一化为 null；这里只做非空传递，不替端点猜音色
            options.voice(voice);
        }
        try {
            TextToSpeechResponse response = textToSpeechModel.call(new TextToSpeechPrompt(text, options.build()));
            Speech speech = response == null ? null : response.getResult();
            return speech == null ? null : speech.getOutput();
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
    }

    /** 平台输出格式到"厂商响应格式 + 产物 MIME"的固定映射；映射之外按输入不合规拒绝（不猜格式）。 */
    static SpeechFormat speechFormatOf(String outputFormat) {
        String normalized = outputFormat == null ? "" : outputFormat.trim().toLowerCase(Locale.ROOT);
        SpeechFormat format = SPEECH_FORMATS.get(normalized);
        if (format == null) {
            throw new ModelException(ModelException.Reason.MEDIA_INPUT_INVALID, "不支持的语音输出格式：" + outputFormat);
        }
        return format;
    }

    /**
     * 语音输出格式的映射结果：厂商响应格式（请求侧）与平台产物 MIME（结果侧）必须来自同一处，
     * 避免两侧各自 switch 出现"请求 mp3 却按 wav 落库"的不一致。
     *
     * @param vendorFormat 厂商响应格式
     * @param mimeType     平台产物 MIME（冻结的音频 MIME 白名单内取值）
     */
    record SpeechFormat(OpenAiAudioApi.SpeechRequest.AudioResponseFormat vendorFormat, String mimeType) {}

    /** 探测音频资源：{@code ByteArrayResource} 默认没有文件名，而厂商转写是 multipart 上传，
     * 必须给出稳定文件名（扩展名与合成 WAV 夹具一致），否则上游会按未知类型拒绝。 */
    private static final class ProbeAudioResource extends ByteArrayResource {

        ProbeAudioResource(byte[] audioBytes) {
            super(audioBytes);
        }

        @Override
        public String getFilename() {
            return PROBE_AUDIO_FILE_NAME;
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private ModelResponse callOnce(ModelRequest request) {
        String prompt = request.prompt() == null ? "" : request.prompt();
        try {
            ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
            return toModelResponse(response);
        } catch (Exception exception) {
            throw mapFailure(exception);
        }
    }

    /**
     * 有界重试：只对可重试原因（超时、限流、上游失败）重发，次数由
     * {@link AiModelProperties#getMaxAttempts()} 限定；能力缺失、输入非法、上游明确拒绝与
     * 输出越界都不重试。
     */
    private <T> T withRetry(Supplier<T> call) {
        int attempts = Math.max(1, properties.getMaxAttempts());
        ModelException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.get();
            } catch (ModelException exception) {
                if (!isRetryable(exception.getReason()) || attempt == attempts) {
                    throw exception;
                }
                lastFailure = exception;
                sleepBeforeRetry();
            }
        }
        throw lastFailure == null ? new ModelException(ModelException.Reason.UPSTREAM_FAILED, "模型调用失败") : lastFailure;
    }

    private void sleepBeforeRetry() {
        Duration backoff = properties.getRetryBackoff();
        if (backoff == null || backoff.isZero() || backoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.Reason.TIMEOUT, "重试等待被中断", exception);
        }
    }

    /** 可重试原因白名单：其余原因重发没有意义，只会放大上游压力。 */
    static boolean isRetryable(ModelException.Reason reason) {
        return reason == ModelException.Reason.TIMEOUT
                || reason == ModelException.Reason.RATE_LIMITED
                || reason == ModelException.Reason.UPSTREAM_FAILED;
    }

    private ModelResponse toModelResponse(ChatResponse response) {
        String text = "";
        String finishReason = null;
        List<ModelToolCall> toolCalls = List.of();
        if (response != null && response.getResult() != null) {
            Generation generation = response.getResult();
            AssistantMessage output = generation.getOutput();
            if (output != null && output.getText() != null) {
                text = output.getText();
            }
            if (output != null) {
                toolCalls = toToolCalls(output.getToolCalls());
            }
            if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
                finishReason = generation.getMetadata().getFinishReason();
            }
        }
        return new ModelResponse(text, toUsage(response), toolCalls, snapshot.modelId(), finishReason);
    }

    /**
     * 厂商用量映射：Spring AI 用 {@link EmptyUsage} 表示"上游没有提供用量"，
     * 而它的 getter 会把缺失计数报成 0。这里必须还原为 {@link ModelUsage#UNKNOWN}，
     * 否则平台会把"没给"显示成假的 0（AT-060 明确禁止）。
     */
    static ModelUsage toUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return ModelUsage.UNKNOWN;
        }
        return toUsage(response.getMetadata().getUsage());
    }

    /** 嵌入响应与聊天响应共用同一套用量语义（见 {@link #toUsage(ChatResponse)}）。 */
    static ModelUsage toUsage(Usage vendorUsage) {
        if (vendorUsage == null || vendorUsage instanceof EmptyUsage) {
            return ModelUsage.UNKNOWN;
        }
        return ModelUsage.of(vendorUsage.getPromptTokens(), vendorUsage.getCompletionTokens());
    }

    private static List<ModelToolCall> toToolCalls(List<AssistantMessage.ToolCall> vendorCalls) {
        if (vendorCalls == null || vendorCalls.isEmpty()) {
            return List.of();
        }
        List<ModelToolCall> calls = new ArrayList<>(vendorCalls.size());
        for (AssistantMessage.ToolCall call : vendorCalls) {
            calls.add(new ModelToolCall(call.id(), call.name(), call.arguments()));
        }
        return calls;
    }

    private static String buildStructuredPrompt(StructuredModelRequest request) {
        String prompt = request.prompt() == null ? "" : request.prompt();
        return STRUCTURED_INSTRUCTION + request.jsonSchema() + "\n\n输入：\n" + prompt;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new ModelException(ModelException.Reason.UPSTREAM_FAILED, "模型客户端已关闭");
        }
    }

    /** 能力缺失必须在调用前失败，并在消息中指明缺失的能力。 */
    private void requireCapability(ModelCapability capability) {
        if (!capabilities.contains(capability)) {
            throw new ModelException(ModelException.Reason.CAPABILITY_UNSUPPORTED, "端点不支持所需能力：" + capability);
        }
    }

    /** 失败映射：只暴露稳定原因，不回传厂商原始报文。 */
    static ModelException mapFailure(Exception exception) {
        if (exception instanceof ModelException modelException) {
            return modelException;
        }
        String name = exception.getClass().getName().toLowerCase(Locale.ROOT);
        if (name.contains("timeout")) {
            return new ModelException(ModelException.Reason.TIMEOUT, "模型调用超时", exception);
        }
        if (name.contains("nontransient")) {
            return new ModelException(ModelException.Reason.UPSTREAM_REJECTED, "上游拒绝了模型调用", exception);
        }
        if (name.contains("transient")) {
            return new ModelException(ModelException.Reason.RATE_LIMITED, "上游限流或短暂不可用", exception);
        }
        return new ModelException(ModelException.Reason.UPSTREAM_FAILED, "模型调用失败", exception);
    }

    /** 关闭客户端：释放厂商资源并拒绝后续请求。 */
    public void close() {
        closed.set(true);
    }

    public ModelEndpointSnapshot snapshot() {
        return snapshot;
    }
}
