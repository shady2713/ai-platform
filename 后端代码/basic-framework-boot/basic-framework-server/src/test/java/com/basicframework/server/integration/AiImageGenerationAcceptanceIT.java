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
import com.basicframework.framework.ai.core.model.media.ImageEditRequest;
import com.basicframework.framework.ai.core.model.media.ImageGenerationRequest;
import com.basicframework.framework.ai.core.model.media.ImageResult;
import com.basicframework.framework.ai.core.model.media.MediaArtifact;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.job.AiMediaTaskJob;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.image.AiImageService;
import com.basicframework.module.ai.service.image.dto.AiImageEditDTO;
import com.basicframework.module.ai.service.image.dto.AiImageGenerateDTO;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskLeaseDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import com.basicframework.module.infra.service.file.FileConfigService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * X03 图片生成与编辑端到端（真实 MySQL + Redis + infra 受控文件存储 + 常驻 Job）。
 *
 * <p>模型端口是**合成图片替身**（{@code @Primary ModelClientFactory}）：真实供应商生成/编辑需要出网与真实凭据
 * （X03 登记的未验证项）；本用例验证平台语义——受理即落库（固定端点与配置版本）、幂等键只受理一次、
 * 底图两端核验、产物先验后存（非图片字节整笔失败且不落半张图）、取消只对未开始任务生效、
 * 越权与不存在同语义、用量只用上游真实计数（缺失记 UNKNOWN 且数值为空）。
 *
 * <p>Quartz 在本用例关闭（{@code spring.quartz.auto-startup=false}）：任务由测试显式调用
 * {@link AiMediaTaskJob#execute(String)} 驱动，避免定时触发与手工触发竞争造成不确定结果。
 */
@Import({
    AiImageGenerationAcceptanceIT.ImageGenerationTestConfiguration.class,
    AiImageGenerationAcceptanceIT.ScopeResolverConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiImageGenerationAcceptanceIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-image-app";

    private static final String MODEL_ID = "it-image-model";

    private static final String PROMPT = "it 一只坐着的橘猫";

    private static final String INSTRUCTION = "it 去掉水印";

    /** 上游报的 token 用量（prompt + completion），任务行必须如实记录。 */
    private static final int REPORTED_TOKENS = 120 + 180;

    /** 媒体任务 Job 必须由测试驱动，不参与 Quartz 调度。 */
    @DynamicPropertySource
    static void disableQuartzForDeterministicJobRuns(DynamicPropertyRegistry registry) {
        registry.add("spring.quartz.auto-startup", () -> "false");
    }

    /** 与既有 AI IT 同源：换票的范围解析需要可信解析器，测试里给出受控范围（不改生产解析）。 */
    @TestConfiguration
    static class ScopeResolverConfiguration {

        @Bean
        SubjectScopeResolver imageGenerationScopeResolver() {
            return request ->
                    Optional.of(new SubjectScope(Set.of(10L), Set.of(), request.scopeSource(), request.scopeVersion()));
        }
    }

    /** 合成图片端口替身：可切换"产物不是图片"与"上游不给用量"，并记录调用与最近一次编辑请求。 */
    @TestConfiguration
    static class ImageGenerationTestConfiguration {

        static final AtomicInteger GENERATE_CALLS = new AtomicInteger();

        static final AtomicInteger EDIT_CALLS = new AtomicInteger();

        static final AtomicReference<ImageEditRequest> LAST_EDIT_REQUEST = new AtomicReference<>();

        static final AtomicReference<ModelUsage> USAGE = new AtomicReference<>(ModelUsage.of(120, 180));

        /** 上游返回 HTML 字节（伪装成 image/png）的开关。 */
        static final AtomicReference<Boolean> HTML_ARTIFACT = new AtomicReference<>(false);

        /** 端口调用前重置计数与开关，保证用例之间互不影响。 */
        static void reset() {
            GENERATE_CALLS.set(0);
            EDIT_CALLS.set(0);
            LAST_EDIT_REQUEST.set(null);
            USAGE.set(ModelUsage.of(120, 180));
            HTML_ARTIFACT.set(false);
        }

        @Bean
        @Primary
        ModelClientFactory fixtureModelClientFactory() {
            ModelPort port = new ModelPort() {

                @Override
                public Set<ModelCapability> capabilities() {
                    return Set.of(ModelCapability.IMAGE_GENERATION, ModelCapability.IMAGE_EDIT);
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
                    // 替身端点声明什么就确认什么：探测语义由 M04/X02 单测覆盖，这里让媒体准入可达
                    return ModelProbeResult.supported(kind, null, 1L);
                }

                @Override
                public ImageResult generateImage(ImageGenerationRequest request) {
                    GENERATE_CALLS.incrementAndGet();
                    if (Boolean.TRUE.equals(HTML_ARTIFACT.get())) {
                        return ImageResult.of(
                                new MediaArtifact(
                                        "image/png",
                                        "<html><body>vendor error page</body></html>".getBytes(StandardCharsets.UTF_8),
                                        null,
                                        null,
                                        null,
                                        null),
                                ModelUsage.UNKNOWN,
                                MODEL_ID);
                    }
                    int count = request.count() == null ? 1 : request.count();
                    List<MediaArtifact> artifacts = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        // 每张尺寸不同：产物行的宽高必须来自真实字节解析，而不是上游声明
                        artifacts.add(new MediaArtifact("image/png", png(32 + index * 8, 24), null, null, null, null));
                    }
                    return new ImageResult(artifacts, USAGE.get(), MODEL_ID, "stop");
                }

                @Override
                public ImageResult editImage(ImageEditRequest request) {
                    EDIT_CALLS.incrementAndGet();
                    LAST_EDIT_REQUEST.set(request);
                    return ImageResult.of(
                            new MediaArtifact("image/png", png(64, 48), null, null, null, null), USAGE.get(), MODEL_ID);
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
    private AiImageService imageService;

    @Autowired
    private AiMediaTaskService taskService;

    @Autowired
    private AiMediaTaskJob mediaTaskJob;

    @Autowired
    private AiFileService fileService;

    @Autowired
    private AiModelEndpointService endpointService;

    @Autowired
    private AiModelCapabilityProbeService probeService;

    @Autowired
    private AiApplicationService applicationService;

    @Autowired
    private AiTicketService ticketService;

    @Autowired
    private AiSubjectService subjectService;

    @Autowired
    private FileConfigService fileConfigService;

    private Long fileConfigId;

    private String applicationSecret;

    private Long imageEndpointId;

    @BeforeEach
    void prepare() {
        cleanUp();
        ImageGenerationTestConfiguration.reset();
        prepareFileStorageAndApplication();
        imageEndpointId = createImageEndpoint();
        loginAs("alice");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        jdbcTemplate.update("DELETE FROM ai_media_asset");
        jdbcTemplate.update("DELETE FROM ai_media_task");
        List<Long> endpointIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_model_endpoint WHERE name LIKE 'it-image-%'", Long.class);
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
        imageEndpointId = null;
    }

    @Test
    void generateAcceptsQueuedTaskThenJobStoresVerifiedAssetsAndReportedUsage() {
        Integer expectedConfigRevision =
                endpointService.getEndpoint(imageEndpointId).getConfigRevision();

        AiMediaTaskResultDTO accepted = imageService.generate(new AiImageGenerateDTO()
                .setRequestKey("it-image-0001")
                .setEndpointId(imageEndpointId)
                .setPrompt(PROMPT)
                .setSize("1024x1024")
                .setCount(2)
                .setOutputFormat("png"));

        // 受理即落库：QUEUED、固定端点与配置版本、模型标识、用量未知且不写 0
        Map<String, Object> queued = taskRow(accepted.getId());
        assertThat(queued.get("status")).isEqualTo(AiMediaTaskDO.STATUS_QUEUED);
        assertThat(queued.get("media_kind")).isEqualTo(AiMediaTaskDO.KIND_IMAGE);
        assertThat(queued.get("operation")).isEqualTo(AiMediaTaskDO.OPERATION_GENERATE);
        assertThat(queued.get("capability")).isEqualTo(ModelCapability.IMAGE_GENERATION.name());
        assertThat(queued.get("endpoint_id")).isEqualTo(imageEndpointId);
        assertThat(queued.get("endpoint_config_revision")).isEqualTo(expectedConfigRevision);
        assertThat(queued.get("model_ref")).isEqualTo(MODEL_ID);
        assertThat(queued.get("input_text")).isEqualTo(PROMPT);
        assertThat(queued.get("output_count")).isEqualTo(2);
        assertThat(queued.get("result_count")).isEqualTo(0);
        assertThat(queued.get("usage_source")).isEqualTo(AiMediaTaskDO.USAGE_SOURCE_UNKNOWN);
        assertThat(queued.get("usage_quantity")).as("上游尚未调用时不得写 0").isNull();
        assertThat(assetRows(accepted.getId())).isEmpty();

        String result = mediaTaskJob.execute(null);

        assertThat(result).isEqualTo("recovered=0,claimed=1,succeeded=1,failed=0");
        Map<String, Object> succeeded = taskRow(accepted.getId());
        assertThat(succeeded.get("status")).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(succeeded.get("failure_code")).isNull();
        assertThat(succeeded.get("result_count")).isEqualTo(2);
        assertThat(succeeded.get("usage_source")).isEqualTo(AiMediaTaskDO.USAGE_SOURCE_REPORTED);
        assertThat(succeeded.get("usage_unit")).isEqualTo("TOKEN");
        assertThat(succeeded.get("usage_quantity")).isEqualTo((long) REPORTED_TOKENS);
        assertThat(succeeded.get("lease_owner")).isNull();
        assertThat(ImageGenerationTestConfiguration.GENERATE_CALLS.get()).isEqualTo(1);

        // 产物行：序号升序、私有文件可读回、字节/尺寸/摘要与真实内容一致
        List<Map<String, Object>> assets = assetRows(accepted.getId());
        assertThat(assets).hasSize(2);
        for (int index = 0; index < assets.size(); index++) {
            Map<String, Object> asset = assets.get(index);
            assertThat(asset.get("ordinal")).isEqualTo(index + 1);
            assertThat(asset.get("mime_type")).isEqualTo("image/png");
            assertThat(asset.get("duration_millis")).isNull();
            Long fileId = ((Number) asset.get("file_id")).longValue();
            byte[] stored = fileService.read(fileId);
            byte[] expected = png(32 + index * 8, 24);
            assertThat(stored).as("落库字节与上游产物逐字节一致").isEqualTo(expected);
            assertThat(((Number) asset.get("size_bytes")).longValue()).isEqualTo(expected.length);
            assertThat(asset.get("sha256")).isEqualTo(sha256(expected));
            assertThat(asset.get("width")).isEqualTo(32 + index * 8);
            assertThat(asset.get("height")).isEqualTo(24);
            assertThat(imageDimensions(stored)).as("产物宽高来自真实字节解析").containsExactly(32 + index * 8, 24);
        }

        // 服务层读回的任务事实与产物一致
        AiMediaTaskResultDTO fetched = taskService.getTask(accepted.getId());
        assertThat(fetched.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(fetched.getUsageSource()).isEqualTo(AiMediaTaskDO.USAGE_SOURCE_REPORTED);
        assertThat(fetched.getUsageQuantity()).isEqualTo(REPORTED_TOKENS);
        assertThat(fetched.getAssets()).hasSize(2);
    }

    @Test
    void missingUpstreamUsageIsStoredAsUnknownWithNullQuantity() {
        ImageGenerationTestConfiguration.USAGE.set(ModelUsage.UNKNOWN);

        AiMediaTaskResultDTO accepted = imageService.generate(generateRequest("it-image-0002", 1));

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=1,failed=0");
        Map<String, Object> row = taskRow(accepted.getId());
        assertThat(row.get("status")).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(row.get("usage_source")).isEqualTo(AiMediaTaskDO.USAGE_SOURCE_UNKNOWN);
        assertThat(row.get("usage_unit")).isNull();
        assertThat(row.get("usage_quantity")).as("上游缺失不得用 0 冒充真实计量").isNull();
        assertThat(assetRows(accepted.getId())).hasSize(1);
    }

    @Test
    void repeatedRequestKeyReturnsSameTaskAndCallsThePortOnlyOnce() {
        AiMediaTaskResultDTO first = imageService.generate(generateRequest("it-image-0003", 1));
        AiMediaTaskResultDTO repeated = imageService.generate(generateRequest("it-image-0003", 1));

        assertThat(repeated.getId()).as("同键重复提交返回同一任务").isEqualTo(first.getId());
        assertThat(taskCountForRequestKey("it-image-0003")).as("同键只允许一行").isEqualTo(1L);

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=1,failed=0");

        assertThat(ImageGenerationTestConfiguration.GENERATE_CALLS.get())
                .as("一次任务只发生一次上游调用")
                .isEqualTo(1);
        assertThat(taskRow(first.getId()).get("status")).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(assetRows(first.getId())).hasSize(1);
    }

    @Test
    void sameKeyWithDifferentPromptIsRejectedAsIdempotencyConflict() {
        imageService.generate(generateRequest("it-image-0004", 1));

        assertCode(
                () -> imageService.generate(new AiImageGenerateDTO()
                        .setRequestKey("it-image-0004")
                        .setEndpointId(imageEndpointId)
                        .setPrompt("it 换一个提示词")
                        .setOutputFormat("png")),
                AiErrorCodeConstants.AI_IDEMPOTENCY_CONFLICT);

        assertThat(taskCountForRequestKey("it-image-0004")).isEqualTo(1L);
        assertThat(ImageGenerationTestConfiguration.GENERATE_CALLS.get())
                .as("冲突拒绝不得触发上游调用")
                .isZero();
    }

    @Test
    void editPassesVerifiedPrivateSourceReferenceToThePort() {
        byte[] source = png(64, 48);
        Long sourceFileId = uploadChatImage("it-edit-source.png", source);

        AiMediaTaskResultDTO accepted = imageService.edit(new AiImageEditDTO()
                .setRequestKey("it-image-0005")
                .setEndpointId(imageEndpointId)
                .setInstruction(INSTRUCTION)
                .setSourceFileId(sourceFileId)
                .setSourceMime("image/png")
                .setSourceSizeBytes((long) source.length)
                .setSourceSha256(sha256(source))
                .setOutputFormat("png"));

        Map<String, Object> queued = taskRow(accepted.getId());
        assertThat(queued.get("operation")).isEqualTo(AiMediaTaskDO.OPERATION_EDIT);
        assertThat(queued.get("capability")).isEqualTo(ModelCapability.IMAGE_EDIT.name());
        assertThat(queued.get("source_file_id")).isEqualTo(sourceFileId);
        assertThat(queued.get("source_mime")).isEqualTo("image/png");
        assertThat(queued.get("source_size_bytes")).isEqualTo((long) source.length);
        assertThat(queued.get("source_sha256")).isEqualTo(sha256(source));

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=1,failed=0");

        assertThat(ImageGenerationTestConfiguration.EDIT_CALLS.get()).isEqualTo(1);
        ImageEditRequest editRequest = ImageGenerationTestConfiguration.LAST_EDIT_REQUEST.get();
        assertThat(editRequest.modelId()).isEqualTo(MODEL_ID);
        assertThat(editRequest.instruction()).isEqualTo(INSTRUCTION);
        assertThat(editRequest.source().fileId()).as("端口收到的是平台私有底图引用").isEqualTo(sourceFileId);
        assertThat(editRequest.source().mimeType()).isEqualTo("image/png");
        assertThat(editRequest.source().sizeBytes()).isEqualTo((long) source.length);
        assertThat(editRequest.source().sha256()).isEqualTo(sha256(source));
        assertThat(taskRow(accepted.getId()).get("status")).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(assetRows(accepted.getId())).hasSize(1);
    }

    @Test
    void editWithUnreadableSourceIsRejectedAtAcceptTimeWithoutOutbound() {
        byte[] source = png(64, 48);
        Long sourceFileId = uploadChatImage("it-edit-foreign.png", source);

        // 1) 他人主体：A07 按不存在处理（防编号枚举），不产生任务、不外发
        loginAs("bob");
        assertCode(
                () -> imageService.edit(editRequest("it-image-0006a", sourceFileId, source)),
                AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertThat(taskCountForRequestKey("it-image-0006a")).isZero();
        assertThat(ImageGenerationTestConfiguration.EDIT_CALLS.get()).isZero();

        // 2) 绑定被解除：受理阶段就拒绝，任务表里不留"排队中"的假象
        loginAs("alice");
        fileService.release(sourceFileId);
        assertCode(
                () -> imageService.edit(editRequest("it-image-0006b", sourceFileId, source)),
                AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertThat(taskCountForRequestKey("it-image-0006b")).isZero();
        assertThat(ImageGenerationTestConfiguration.EDIT_CALLS.get()).isZero();
    }

    @Test
    void nonImageArtifactFailsTaskWithMediaOutputInvalidAndLeavesNoAssetOrPrivateFile() {
        ImageGenerationTestConfiguration.HTML_ARTIFACT.set(true);
        long filesBefore = countInfraFiles();

        AiMediaTaskResultDTO accepted = imageService.generate(generateRequest("it-image-0007", 1));

        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=1,succeeded=0,failed=1");

        Map<String, Object> row = taskRow(accepted.getId());
        assertThat(row.get("status")).isEqualTo(AiMediaTaskDO.STATUS_FAILED);
        assertThat(row.get("failure_code"))
                .as("产物不合规必须落平台错误码（1_003_010_005 的十进制形式）")
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID.getCode()));
        assertThat(row.get("result_count")).isEqualTo(0);
        assertThat(assetRows(accepted.getId())).as("不落半张图").isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_file_binding WHERE business_type = 'ai_media_task' AND business_key = ?",
                        Long.class,
                        String.valueOf(accepted.getId())))
                .as("失败的产物不得留下私有文件引用")
                .isZero();
        assertThat(countInfraFiles()).as("不得留下孤儿文件").isEqualTo(filesBefore);
    }

    @Test
    void cancelWinsOnQueuedTaskButRunningTaskCannotBeCancelled() {
        // QUEUED 可取消：终态写入 CANCELLED，Job 之后不会执行它
        AiMediaTaskResultDTO queued = imageService.generate(generateRequest("it-image-0008a", 1));
        AiMediaTaskResultDTO cancelled = taskService.cancel(queued.getId());
        assertThat(cancelled.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_CANCELLED);
        assertThat(taskRow(queued.getId()).get("status")).isEqualTo(AiMediaTaskDO.STATUS_CANCELLED);
        assertThat(mediaTaskJob.execute(null)).isEqualTo("recovered=0,claimed=0,succeeded=0,failed=0");
        assertThat(taskRow(queued.getId()).get("status")).isEqualTo(AiMediaTaskDO.STATUS_CANCELLED);
        assertThat(ImageGenerationTestConfiguration.GENERATE_CALLS.get()).isZero();

        // RUNNING 不可取消：上游调用已发出，按状态冲突拒绝而不是谎报"已取消"
        AiMediaTaskResultDTO running = imageService.generate(generateRequest("it-image-0008b", 1));
        List<AiMediaTaskLeaseDTO> leases = taskService.claim("it-image-manual-worker", 10, 300);
        assertThat(leases).extracting(AiMediaTaskLeaseDTO::getTaskId).contains(running.getId());
        assertThat(taskRow(running.getId()).get("status")).isEqualTo(AiMediaTaskDO.STATUS_RUNNING);

        assertCode(() -> taskService.cancel(running.getId()), AiErrorCodeConstants.AI_STATE_CONFLICT);

        assertThat(taskRow(running.getId()).get("status"))
                .as("拒绝取消后状态保持 RUNNING")
                .isEqualTo(AiMediaTaskDO.STATUS_RUNNING);
        assertThat(taskRow(running.getId()).get("lease_owner")).isEqualTo("it-image-manual-worker");
    }

    @Test
    void anotherSubjectCannotReadOrCancelTheTask() {
        AiMediaTaskResultDTO accepted = imageService.generate(generateRequest("it-image-0009", 1));

        loginAs("bob");
        assertCode(() -> taskService.getTask(accepted.getId()), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertCode(() -> taskService.cancel(accepted.getId()), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertCode(() -> taskService.getAssets(accepted.getId()), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertThat(taskService.getTaskPage(new PageParam(), null, null).getList())
                .as("他人任务不出现在分页里")
                .isEmpty();

        assertThat(taskRow(accepted.getId()).get("status")).as("越权取消不得改变任务状态").isEqualTo(AiMediaTaskDO.STATUS_QUEUED);
        assertThat(ImageGenerationTestConfiguration.GENERATE_CALLS.get()).isZero();
    }

    private AiImageGenerateDTO generateRequest(String requestKey, int count) {
        return new AiImageGenerateDTO()
                .setRequestKey(requestKey)
                .setEndpointId(imageEndpointId)
                .setPrompt(PROMPT)
                .setSize("1024x1024")
                .setCount(count)
                .setOutputFormat("png");
    }

    private AiImageEditDTO editRequest(String requestKey, Long sourceFileId, byte[] source) {
        return new AiImageEditDTO()
                .setRequestKey(requestKey)
                .setEndpointId(imageEndpointId)
                .setInstruction(INSTRUCTION)
                .setSourceFileId(sourceFileId)
                .setSourceMime("image/png")
                .setSourceSizeBytes((long) source.length)
                .setSourceSha256(sha256(source))
                .setOutputFormat("png");
    }

    private void prepareFileStorageAndApplication() {
        fileConfigId = fileConfigService.createFileConfig(
                new FileConfigDO()
                        .setName("it-ai-image-" + System.nanoTime())
                        .setStorage(FileStorageEnum.DB.getStorage()),
                Map.of("domain", "http://localhost/files"));
        fileConfigService.updateFileConfigMaster(fileConfigId);
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 图片生成应用")
                .setOrigins(List.of("https://crm.example.com")));
        applicationService.updateStatus(issue.getApplication().getId(), 0, true);
        applicationSecret = issue.getSecret();
        subjectService.syncSubject(
                issue.getApplication().getId(), AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        subjectService.syncSubject(issue.getApplication().getId(), AiSubjectType.USER, "bob", "Bob", "crm-auth", 1L);
    }

    private Long createImageEndpoint() {
        AiModelEndpointSaveDTO saveDTO = new AiModelEndpointSaveDTO();
        saveDTO.setName("it-image-generation");
        saveDTO.setProvider("openai_compatible");
        saveDTO.setBaseUrl("https://it-image.example.com/v1");
        saveDTO.setModelId(MODEL_ID);
        saveDTO.setCapabilities(List.of(ModelCapability.IMAGE_GENERATION.name(), ModelCapability.IMAGE_EDIT.name()));
        saveDTO.setCredential("sk-it-image");
        Long endpointId = endpointService.createEndpoint(saveDTO);
        AiModelEndpointDO created = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, created.getVersion(), true);
        // 真实探测走替身端口：让"能力已声明 + 探测已确认"两条准入判据真的成立
        probeService.probeAll(endpointId);
        return endpointId;
    }

    private Long uploadChatImage(String fileName, byte[] content) {
        return fileService
                .upload("ai_chat_session", "session-alice", fileName, "image/png", content)
                .getFileId();
    }

    private Map<String, Object> taskRow(Long taskId) {
        return jdbcTemplate.queryForMap("SELECT * FROM ai_media_task WHERE id = ?", taskId);
    }

    private List<Map<String, Object>> assetRows(Long taskId) {
        return jdbcTemplate.queryForList("SELECT * FROM ai_media_asset WHERE task_id = ? ORDER BY ordinal", taskId);
    }

    private Long taskCountForRequestKey(String requestKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_media_task WHERE request_key = ?", Long.class, requestKey);
    }

    private long countInfraFiles() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM infra_file WHERE config_id = ?", Long.class, fileConfigId);
    }

    private void loginAs(String externalUserId) {
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

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(expected.getCode()));
    }

    private static byte[] png(int width, int height) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("测试图片编码失败", failure);
        }
    }

    private static List<Integer> imageDimensions(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(content));
            return List.of(image.getWidth(), image.getHeight());
        } catch (IOException failure) {
            throw new IllegalStateException("测试图片解析失败", failure);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            throw new IllegalStateException("SHA-256 不可用", missingAlgorithm);
        }
    }
}
