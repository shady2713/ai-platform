package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.EmbeddingResponse;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelClientFactory;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelProbeKind;
import com.basicframework.framework.ai.core.model.ModelProbeResult;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.media.MediaArtifact;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisRequest;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisResponse;
import com.basicframework.framework.ai.core.model.media.SpeechTranscriptionRequest;
import com.basicframework.framework.ai.core.model.media.SpeechTranscriptionResponse;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.job.AiMediaTaskJob;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.speech.AiSpeechService;
import com.basicframework.module.ai.service.speech.dto.AiSpeechSynthesizeDTO;
import com.basicframework.module.ai.service.speech.dto.AiSpeechTranscribeDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import com.basicframework.module.infra.service.file.FileConfigService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 非实时语音验收夹具（X04）：应用/主体/票据、文件存储、语音端点与探针替身、私有音频夹具。
 *
 * <p>拆成两个用例类共用本基类，是为了守住"单文件 800 行"的源码质量门禁；夹具语义只有一份：
 * <ol>
 *   <li>Quartz 在用例里关闭（{@code spring.quartz.auto-start=true} 被置为 false），任务只由测试显式调用
 *       {@code AiMediaTaskJob#execute(String)} 驱动，避免定时触发与手工触发竞争；</li>
 *   <li>转写源音频用"平台上已存在一份私有音频"的夹具（直接写 infra 文件行 + A07 绑定），
 *       读取与授权仍走 A07 全链路（他人主体读不到、解除引用后读不到）；</li>
 *   <li>受控上传端点当前不接受音频后缀（infra 白名单），该事实由用例钉住，不是本例的绕过。</li>
 * </ol>
 */
@Import({
    AiSpeechAcceptanceSupport.SpeechTestConfiguration.class,
    AiSpeechAcceptanceSupport.ScopeResolverConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AiSpeechAcceptanceSupport extends AbstractPersistenceIntegrationTest {

    protected static final String APP_CODE = "it-speech-app";

    protected static final String MODEL_ID = "it-speech-model";

    protected static final String TRANSCRIPT = "it 这是转写全文";

    /** 上游报的 token 用量（prompt + completion），任务行必须如实记录。 */
    protected static final int REPORTED_TOKENS = 40 + 60;

    /** 合成产物时长（毫秒），端口给出的真实时长必须落在产物行。 */
    protected static final long AUDIO_DURATION_MILLIS = 1_500L;

    /** infra 受控上传的类型白名单拒绝位（1_001_003_003）。 */
    protected static final int FILE_TYPE_NOT_ALLOWED_CODE = 1_001_003_003;

    /** 媒体任务 Job 必须由测试驱动，不参与 Quartz 调度。 */
    @DynamicPropertySource
    static void disableQuartzForDeterministicJobRuns(DynamicPropertyRegistry registry) {
        registry.add("spring.quartz.auto-startup", () -> "false");
    }

    /** 与既有 AI IT 同源：换票的范围解析需要可信解析器，测试里给出受控范围（不改生产解析）。 */
    @TestConfiguration
    static class ScopeResolverConfiguration {

        @Bean
        SubjectScopeResolver speechScopeResolver() {
            return request ->
                    Optional.of(new SubjectScope(Set.of(10L), Set.of(), request.scopeSource(), request.scopeVersion()));
        }
    }

    /** 合成语音端口替身：记录调用与最近一次请求，可切换"产物不是音频"与"上游不给用量"。 */
    @TestConfiguration
    static class SpeechTestConfiguration {

        static final AtomicInteger TRANSCRIBE_CALLS = new AtomicInteger();

        static final AtomicInteger SYNTHESIZE_CALLS = new AtomicInteger();

        static final AtomicReference<SpeechTranscriptionRequest> LAST_TRANSCRIPTION = new AtomicReference<>();

        static final AtomicReference<SpeechSynthesisRequest> LAST_SYNTHESIS = new AtomicReference<>();

        static final AtomicReference<ModelUsage> USAGE = new AtomicReference<>(ModelUsage.of(40, 60));

        /** 上游返回 HTML 字节（伪装成 audio/mpeg）的开关。 */
        static final AtomicReference<Boolean> HTML_ARTIFACT = new AtomicReference<>(false);

        /** 上游换算出的产物时长（超过平台上限时任务必须按稳定码失败）。 */
        static final AtomicReference<Long> ARTIFACT_DURATION = new AtomicReference<>(AUDIO_DURATION_MILLIS);

        static void reset() {
            TRANSCRIBE_CALLS.set(0);
            SYNTHESIZE_CALLS.set(0);
            LAST_TRANSCRIPTION.set(null);
            LAST_SYNTHESIS.set(null);
            USAGE.set(ModelUsage.of(40, 60));
            HTML_ARTIFACT.set(false);
            ARTIFACT_DURATION.set(AUDIO_DURATION_MILLIS);
        }

        @Bean
        @Primary
        ModelClientFactory fixtureModelClientFactory() {
            ModelPort port = new ModelPort() {

                @Override
                public Set<ModelCapability> capabilities() {
                    return Set.of(ModelCapability.SPEECH_TO_TEXT, ModelCapability.TEXT_TO_SPEECH);
                }

                @Override
                public ModelResponse generate(ModelRequest request) {
                    throw new ModelException(ModelException.Reason.CAPABILITY_UNSUPPORTED, "文本调用不应发生");
                }

                @Override
                public EmbeddingResponse embed(EmbeddingRequest request) {
                    throw new ModelException(ModelException.Reason.CAPABILITY_UNSUPPORTED, "嵌入调用不应发生");
                }

                @Override
                public ModelProbeResult probe(ModelProbeKind kind) {
                    // 替身端点声明什么就确认什么：探测语义由适配器与探测服务单测覆盖，这里让媒体准入可达
                    return ModelProbeResult.supported(kind, null, 1L);
                }

                @Override
                public SpeechTranscriptionResponse transcribeSpeech(SpeechTranscriptionRequest request) {
                    TRANSCRIBE_CALLS.incrementAndGet();
                    LAST_TRANSCRIPTION.set(request);
                    return SpeechTranscriptionResponse.of(TRANSCRIPT, USAGE.get(), MODEL_ID);
                }

                @Override
                public SpeechSynthesisResponse synthesizeSpeech(SpeechSynthesisRequest request) {
                    SYNTHESIZE_CALLS.incrementAndGet();
                    LAST_SYNTHESIS.set(request);
                    if (Boolean.TRUE.equals(HTML_ARTIFACT.get())) {
                        return new SpeechSynthesisResponse(
                                new MediaArtifact(
                                        "audio/mpeg",
                                        "<html><body>vendor error page</body></html>".getBytes(StandardCharsets.UTF_8),
                                        null,
                                        null,
                                        null,
                                        null),
                                ModelUsage.UNKNOWN,
                                MODEL_ID);
                    }
                    return new SpeechSynthesisResponse(
                            new MediaArtifact("audio/mpeg", id3(256), null, null, null, ARTIFACT_DURATION.get()),
                            USAGE.get(),
                            MODEL_ID);
                }
            };
            return new ModelClientFactory() {

                @Override
                public ModelPort getOrCreate(ModelEndpointSnapshot snapshot) {
                    return port;
                }

                @Override
                public void invalidate(Long endpointId) {
                    // 替身工厂不缓存客户端，无需失效
                }

                @Override
                public void close() {
                    // 替身工厂不持有资源
                }
            };
        }
    }

    @Autowired
    protected AiSpeechService speechService;

    @Autowired
    protected AiMediaTaskService taskService;

    @Autowired
    protected AiMediaTaskJob mediaTaskJob;

    @Autowired
    protected AiFileService fileService;

    @Autowired
    protected AiModelEndpointService endpointService;

    @Autowired
    protected AiModelCapabilityProbeService probeService;

    @Autowired
    protected AiApplicationService applicationService;

    @Autowired
    protected AiTicketService ticketService;

    @Autowired
    protected AiSubjectService subjectService;

    @Autowired
    protected FileConfigService fileConfigService;

    protected Long fileConfigId;

    protected Long applicationId;

    protected String applicationSecret;

    protected Long speechEndpointId;

    @BeforeEach
    void prepare() {
        cleanUp();
        SpeechTestConfiguration.reset();
        prepareFileStorageAndApplication();
        speechEndpointId = createSpeechEndpoint();
        loginAs("alice");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        jdbcTemplate.update("DELETE FROM ai_media_asset");
        jdbcTemplate.update("DELETE FROM ai_media_task");
        List<Long> endpointIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_model_endpoint WHERE name LIKE 'it-speech-%'", Long.class);
        for (Long endpointId : endpointIds) {
            jdbcTemplate.update("DELETE FROM ai_model_probe WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint_revision WHERE endpoint_id = ?", endpointId);
            jdbcTemplate.update("DELETE FROM ai_model_endpoint WHERE id = ?", endpointId);
        }
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            List<Long> fileIds = jdbcTemplate.queryForList(
                    "SELECT file_id FROM ai_file_binding WHERE application_id = ?", Long.class, appId);
            jdbcTemplate.update("DELETE FROM ai_file_binding WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_resource_grant WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_subject WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_access_ticket WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application_credential WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
            for (Long fileId : fileIds) {
                jdbcTemplate.update("DELETE FROM infra_file_content WHERE id = ?", fileId);
                jdbcTemplate.update("DELETE FROM infra_file WHERE id = ?", fileId);
            }
        }
        if (fileConfigId != null) {
            jdbcTemplate.update("DELETE FROM infra_file_content WHERE config_id = ?", fileConfigId);
            jdbcTemplate.update("DELETE FROM infra_file WHERE config_id = ?", fileConfigId);
            jdbcTemplate.update("DELETE FROM infra_file_config WHERE id = ?", fileConfigId);
            fileConfigId = null;
        }
        applicationId = null;
        speechEndpointId = null;
    }

    protected AiSpeechSynthesizeDTO synthesizeRequest(String requestKey) {
        return new AiSpeechSynthesizeDTO()
                .setRequestKey(requestKey)
                .setEndpointId(speechEndpointId)
                .setText("it 欢迎使用中台")
                .setVoice("Alloy")
                .setOutputFormat("mp3");
    }

    protected AiSpeechTranscribeDTO transcribeRequest(String requestKey, Long sourceFileId, byte[] source) {
        return new AiSpeechTranscribeDTO()
                .setRequestKey(requestKey)
                .setEndpointId(speechEndpointId)
                .setSourceFileId(sourceFileId)
                .setSourceMime("audio/wav")
                .setSourceSizeBytes((long) source.length)
                .setSourceSha256(sha256(source))
                .setSourceDurationMillis(8_000L)
                .setLanguageHint("zh-CN");
    }

    protected void prepareFileStorageAndApplication() {
        fileConfigId = fileConfigService.createFileConfig(
                new FileConfigDO()
                        .setName("it-ai-speech-" + System.nanoTime())
                        .setStorage(FileStorageEnum.DB.getStorage()),
                Map.of("domain", "http://localhost/files"));
        fileConfigService.updateFileConfigMaster(fileConfigId);
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 语音应用")
                .setOrigins(List.of("https://crm.example.com")));
        applicationId = issue.getApplication().getId();
        applicationService.updateStatus(applicationId, 0, true);
        applicationSecret = issue.getSecret();
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        subjectService.syncSubject(applicationId, AiSubjectType.USER, "bob", "Bob", "crm-auth", 1L);
    }

    protected Long createSpeechEndpoint() {
        AiModelEndpointSaveDTO saveDTO = new AiModelEndpointSaveDTO();
        saveDTO.setName("it-speech-generation");
        saveDTO.setProvider("openai_compatible");
        saveDTO.setBaseUrl("https://it-speech.example.com/v1");
        saveDTO.setModelId(MODEL_ID);
        saveDTO.setCapabilities(List.of(ModelCapability.SPEECH_TO_TEXT.name(), ModelCapability.TEXT_TO_SPEECH.name()));
        saveDTO.setCredential("sk-it-speech");
        Long endpointId = endpointService.createEndpoint(saveDTO);
        AiModelEndpointDO created = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, created.getVersion(), true);
        // 真实探测走替身端口：让"能力已声明 + 探测已确认"两条准入判据真的成立
        probeService.probeAll(endpointId);
        return endpointId;
    }

    /**
     * "平台上已存在一份私有音频"的夹具（登记缺口的绕行，见类注释）：直接写 infra 文件行与 A07 绑定，
     * 读取与授权仍走 A07 全链路（他人主体读不到、解除引用后读不到）。
     */
    protected Long insertPrivateAudioFixture(String fileName, byte[] content) {
        String path = "it-speech/" + fileName;
        jdbcTemplate.update(
                "INSERT INTO infra_file (config_id, name, path, url, type, size, access_type, upload_status,"
                        + " owner_user_id, owner_user_type, business_type, business_id, creator, updater)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 2, 1, ?, ?, 'ai_chat_session', 0, '1', '1')",
                fileConfigId,
                fileName,
                path,
                "http://localhost/files/" + path,
                "audio/wav",
                content.length,
                1L,
                UserTypeEnum.MEMBER.getValue());
        Long fileId = jdbcTemplate.queryForObject(
                "SELECT id FROM infra_file WHERE config_id = ? AND path = ?", Long.class, fileConfigId, path);
        jdbcTemplate.update(
                "INSERT INTO infra_file_content (config_id, path, content, creator, updater) VALUES (?, ?, ?, '1', '1')",
                fileConfigId,
                path,
                content);
        jdbcTemplate.update(
                "INSERT INTO ai_file_binding (file_id, business_type, business_key, application_id, subject_type,"
                        + " external_user_id, status, version, creator, updater)"
                        + " VALUES (?, 'ai_chat_session', 'session-alice', ?, 'USER', 'alice', 'ACTIVE', 0, '1', '1')",
                fileId,
                applicationId);
        return fileId;
    }

    protected Map<String, Object> taskRow(Long taskId) {
        return jdbcTemplate.queryForMap("SELECT * FROM ai_media_task WHERE id = ?", taskId);
    }

    protected List<Map<String, Object>> assetRows(Long taskId) {
        return jdbcTemplate.queryForList("SELECT * FROM ai_media_asset WHERE task_id = ? ORDER BY ordinal", taskId);
    }

    protected Long taskCountForRequestKey(String requestKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_media_task WHERE request_key = ?", Long.class, requestKey);
    }

    protected long countInfraFiles() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM infra_file WHERE config_id = ?", Long.class, fileConfigId);
    }

    protected void loginAs(String externalUserId) {
        String ticket = ticketService
                .issue(APP_CODE, applicationSecret, AiSubjectType.USER, externalUserId, List.of())
                .getToken();
        var context = ticketService.verify(ticket);
        LoginUser loginUser = new LoginUser()
                .setId(context.getTicketId())
                .setUserType(UserTypeEnum.MEMBER.getValue())
                .setInfo(Map.of(
                        AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID,
                        String.valueOf(context.getApplicationId()),
                        AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE,
                        context.getSubjectType(),
                        AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID,
                        context.getExternalUserId()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    protected static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(expected.getCode()));
    }

    /** 0.5 秒 8 kHz 单声道 16 位 PCM 的 WAV 夹具（与探测夹具同形，不含真实用户数据）。 */
    protected static byte[] wav() {
        int samples = 4_000;
        byte[] content = new byte[44 + samples * 2];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, content, 8, 4);
        System.arraycopy("fmt ".getBytes(StandardCharsets.US_ASCII), 0, content, 12, 4);
        content[16] = 16;
        content[20] = 1;
        content[22] = 1;
        content[24] = (byte) 0x40;
        content[25] = (byte) 0x1F;
        content[28] = (byte) 0x80;
        content[29] = (byte) 0x3E;
        content[32] = 2;
        content[34] = 16;
        System.arraycopy("data".getBytes(StandardCharsets.US_ASCII), 0, content, 36, 4);
        content[40] = (byte) (samples * 2 & 0xFF);
        content[41] = (byte) ((samples * 2 >> 8) & 0xFF);
        return content;
    }

    /** 合成音频产物夹具：ID3 头 + 填充（不是真实可播放音频，仅用于通道语义断言）。 */
    protected static byte[] id3(int padding) {
        byte[] content = new byte[12 + padding];
        System.arraycopy("ID3".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 3);
        return content;
    }

    protected static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            throw new IllegalStateException("SHA-256 不可用", missingAlgorithm);
        }
    }
}
