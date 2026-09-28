package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.EmbeddingResponse;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelClientFactory;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelProbeKind;
import com.basicframework.framework.ai.core.model.ModelProbeResult;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.media.ImageOcrRequest;
import com.basicframework.framework.ai.core.model.media.MediaTextResponse;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeFilter;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexException;
import com.basicframework.module.ai.adapter.knowledge.KnowledgeIndexPort;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.framework.security.AiUserSessionCommonApi;
import com.basicframework.module.ai.job.AiKnowledgeIngestionJob;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import com.basicframework.module.ai.service.auth.AiTicketService;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import com.basicframework.module.ai.service.document.AiDocumentOcrService;
import com.basicframework.module.ai.service.document.dto.AiDocumentOcrRequestDTO;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexResultDTO;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeBaseSaveDTO;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeEmbeddingClient;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionService;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionRequestDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionResultDTO;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.vision.AiVisionConfidenceSource;
import com.basicframework.module.ai.service.vision.AiVisionRegionSource;
import com.basicframework.module.ai.service.vision.AiVisionService;
import com.basicframework.module.ai.service.vision.dto.AiVisionImageRefDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrRequestDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrResultDTO;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * X02 图片理解 / OCR / 扫描件重索引端到端（真实 MySQL + Redis + infra 受控文件存储）。
 *
 * <p>模型端口是**计数替身**（`@Primary ModelClientFactory`）：真实视觉/OCR 效果需要出网与真实凭据，
 * 属 X02 明确登记的"未验证项"；本用例验证平台语义——准入先于读文件与外发、未声明能力零外发、
 * 无权文件不能识别、OCR 失败不替换旧版本、识别稿按既有入库语义成为新版本并被切片。
 *
 * <p>索引端口与嵌入客户端同样是确定性替身（与 K05 集成用例同款做法）：真实向量服务语义由 K01/K05 覆盖。
 */
@Import({
    AiVisionOcrAcceptanceIT.VisionOcrTestConfiguration.class,
    AiVisionOcrAcceptanceIT.EmbeddingTestConfiguration.class,
    AiVisionOcrAcceptanceIT.ScopeResolverConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiVisionOcrAcceptanceIT extends AbstractPersistenceIntegrationTest {

    private static final String APP_CODE = "it-vision-app";

    private static final String KNOWLEDGE_BASE_CODE = "it-vision-kb";

    private static final int EMBEDDING_DIMENSION = 64;

    private static final String OCR_TEXT = "第 1 页识别文本：合同金额 290.00 元。";

    /** 模型端口替身：媒体调用计数 + 可注入的失败/文本。 */
    /** 与既有 AI IT 同源：A03 的授权判定依赖主体范围解析，测试里给出受控范围（不改生产解析）。 */
    @TestConfiguration
    static class ScopeResolverConfiguration {

        @Bean
        com.basicframework.module.ai.domain.identity.SubjectScopeResolver visionOcrScopeResolver() {
            return request -> java.util.Optional.of(new com.basicframework.module.ai.domain.identity.SubjectScope(
                    java.util.Set.of(10L),
                    java.util.Set.of(KNOWLEDGE_BASE_CODE),
                    request.scopeSource(),
                    request.scopeVersion()));
        }
    }

    @TestConfiguration
    static class VisionOcrTestConfiguration {

        static final AtomicInteger OCR_CALLS = new AtomicInteger();

        static final AtomicReference<String> OCR_TEXT_HOLDER = new AtomicReference<>(OCR_TEXT);

        /** 是否让 OCR 以"上游没有文本"失败（模拟适配器对空产物的处理）。 */
        static final AtomicReference<Boolean> FAIL_OCR = new AtomicReference<>(false);

        @Bean
        @Primary
        ModelClientFactory fixtureModelClientFactory() {
            ModelPort port = new ModelPort() {

                @Override
                public Set<ModelCapability> capabilities() {
                    return Set.of(ModelCapability.TEXT, ModelCapability.IMAGE_OCR, ModelCapability.IMAGE_UNDERSTANDING);
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
                    // 替身端点声明什么就确认什么：探测语义由 M04/X02 单测覆盖，这里让准入可达
                    return ModelProbeResult.supported(kind, null, 1L);
                }

                @Override
                public MediaTextResponse recognizeImageText(ImageOcrRequest request) {
                    OCR_CALLS.incrementAndGet();
                    if (Boolean.TRUE.equals(FAIL_OCR.get())) {
                        throw new ModelException(ModelException.Reason.MEDIA_OUTPUT_EMPTY, "上游未返回文本");
                    }
                    return new MediaTextResponse(
                            OCR_TEXT_HOLDER.get(), ModelUsage.of(11, 22), "it-vision-model", "stop");
                }
            };
            return new com.basicframework.framework.ai.core.model.ModelClientFactory() {
                @Override
                public com.basicframework.framework.ai.core.model.ModelPort getOrCreate(
                        com.basicframework.framework.ai.core.model.ModelEndpointSnapshot snapshot) {
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

    /** 确定性嵌入与内存索引替身：维度与知识库声明一致，同一文本永远同一向量。 */
    @TestConfiguration
    static class EmbeddingTestConfiguration {

        /** 本次运行写入索引的向量点（含切片正文载荷），用于断言识别稿真的进了切片。 */
        static final List<KnowledgeIndexPort.IndexDocument> WRITTEN = new ArrayList<>();

        @Bean
        @Primary
        AiKnowledgeEmbeddingClient fixtureEmbeddingClient() {
            return (embeddingModel, texts) -> {
                List<float[]> vectors = new ArrayList<>(texts.size());
                for (String text : texts) {
                    float[] vector = new float[EMBEDDING_DIMENSION];
                    for (int index = 0; index < EMBEDDING_DIMENSION; index++) {
                        vector[index] = (text.hashCode() + index) % 7;
                    }
                    vectors.add(vector);
                }
                return new AiKnowledgeEmbeddingClient.EmbeddingBatch(vectors, embeddingModel, EMBEDDING_DIMENSION);
            };
        }

        @Bean
        @Primary
        KnowledgeIndexPort fixtureIndexPort() {
            return new KnowledgeIndexPort() {

                private final Map<String, Integer> dimensions = new LinkedHashMap<>();

                @Override
                public CollectionInfo ensureCollection(String collection, int dimension) {
                    Integer existing = dimensions.putIfAbsent(collection, dimension);
                    if (existing != null && existing != dimension) {
                        throw new KnowledgeIndexException(
                                KnowledgeIndexException.Reason.DIMENSION_MISMATCH, "dimension");
                    }
                    return new CollectionInfo(collection, dimension, WRITTEN.size());
                }

                @Override
                public void upsert(String collection, List<IndexDocument> newDocuments) {
                    WRITTEN.addAll(newDocuments);
                }

                @Override
                public List<SearchHit> search(String collection, float[] vector, int topK, KnowledgeFilter filter) {
                    return List.of();
                }

                @Override
                public long delete(String collection, KnowledgeFilter filter) {
                    return 0;
                }

                @Override
                public void deleteAll(String collection) {
                    WRITTEN.clear();
                }

                @Override
                public CollectionInfo describe(String collection) {
                    return new CollectionInfo(collection, dimensions.getOrDefault(collection, 0), WRITTEN.size());
                }
            };
        }
    }

    @Autowired
    private AiVisionService visionService;

    @Autowired
    private AiDocumentOcrService documentOcrService;

    @Autowired
    private AiFileService fileService;

    @Autowired
    private AiKnowledgeBaseService baseService;

    @Autowired
    private AiKnowledgeDocumentService documentService;

    @Autowired
    private AiKnowledgeIngestionService ingestionService;

    @Autowired
    private AiKnowledgeIngestionJob ingestionJob;

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
    private AiResourceGrantService grantService;

    @Autowired
    private FileConfigService fileConfigService;

    private Long fileConfigId;

    private String applicationSecret;

    private Long knowledgeBaseId;

    private Long visionEndpointId;

    @BeforeEach
    void prepare() {
        cleanUp();
        VisionOcrTestConfiguration.OCR_CALLS.set(0);
        VisionOcrTestConfiguration.OCR_TEXT_HOLDER.set(OCR_TEXT);
        VisionOcrTestConfiguration.FAIL_OCR.set(false);
        EmbeddingTestConfiguration.WRITTEN.clear();
        prepareFileStorageAndApplication();
        prepareKnowledgeBase();
        loginAs("alice");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        jdbcTemplate.update("DELETE FROM ai_knowledge_ingestion_task");
        jdbcTemplate.update("DELETE FROM ai_knowledge_chunk");
        jdbcTemplate.update("DELETE FROM ai_knowledge_document_version");
        jdbcTemplate.update("DELETE FROM ai_knowledge_document");
        jdbcTemplate.update("DELETE FROM ai_knowledge_index_generation");
        jdbcTemplate.update("DELETE FROM ai_knowledge_base");
        List<Long> endpointIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_model_endpoint WHERE name LIKE 'it-vision-%'", Long.class);
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
        knowledgeBaseId = null;
        visionEndpointId = null;
    }

    @Test
    void undeclaredCapabilityIsRejectedBeforeReadingFileOrCallingModel() {
        Long textOnlyEndpointId = createEndpoint("it-vision-text-only", List.of("TEXT"));
        UploadedImage image = uploadChatImage("page.png");

        assertCode(
                () -> visionService.recognizeText(new AiVisionOcrRequestDTO()
                        .setEndpointId(textOnlyEndpointId)
                        .setImage(imageRef(image))),
                AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED);

        assertThat(VisionOcrTestConfiguration.OCR_CALLS.get()).as("未声明能力时必须零外发").isZero();
    }

    @Test
    void unownedPrivateFileCannotBeRecognizedAndProducesNoOutbound() {
        // 基线：本人文件可以识别（证明计数替身在工作，零外发断言不是空断言）
        UploadedImage own = uploadChatImage("own.png");
        AiVisionOcrResultDTO recognized = visionService.recognizeText(
                new AiVisionOcrRequestDTO().setEndpointId(visionEndpointId).setImage(imageRef(own)));
        assertThat(recognized.getText()).isEqualTo(OCR_TEXT);
        assertThat(VisionOcrTestConfiguration.OCR_CALLS.get()).isEqualTo(1);

        // 他人会话附件：A07 按不存在处理（防编号枚举），且不触发任何模型调用
        VisionOcrTestConfiguration.OCR_CALLS.set(0);
        jdbcTemplate.update(
                "INSERT INTO ai_file_binding (file_id, business_type, business_key, application_id, subject_type,"
                        + " external_user_id, status, version) VALUES (99001, 'ai_chat_session', 'session-bob', ?,"
                        + " 'USER', 'bob', 'ACTIVE', 0)",
                currentApplicationId());

        assertCode(
                () -> visionService.recognizeText(new AiVisionOcrRequestDTO()
                        .setEndpointId(visionEndpointId)
                        .setImage(imageRef(99001L, 2048L))),
                AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertThat(VisionOcrTestConfiguration.OCR_CALLS.get()).isZero();
    }

    @Test
    void imageOcrReturnsPrivateFileReferenceAndConfidenceProvenance() {
        UploadedImage image = uploadChatImage("invoice.png");

        AiVisionOcrResultDTO result = visionService.recognizeText(new AiVisionOcrRequestDTO()
                .setEndpointId(visionEndpointId)
                .setImage(imageRef(image))
                .setLanguageHint("zh-CN"));

        assertThat(result.getFileId()).as("结果只引用私有文件标识").isEqualTo(image.fileId());
        assertThat(result.getText()).isEqualTo(OCR_TEXT);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getRegionSource()).isEqualTo(AiVisionRegionSource.WHOLE_PAGE);
        assertThat(result.getConfidenceSource()).isEqualTo(AiVisionConfidenceSource.UNKNOWN);
        assertThat(result.getConfidence()).as("上游没有置信度时不得编造数值").isNull();
        assertThat(result.isReviewRequired()).as("机器识别必须标记需人工复核").isTrue();
        assertThat(result.getUsage().source()).isEqualTo("REPORTED");
        assertThat(result.getUsage().quantity()).isEqualTo(33);
    }

    @Test
    void documentOcrReindexesAsNewVersionAndFailedOcrKeepsOldActiveVersion() {
        // 1) 已有可用版本：文本知识文档入库 → 走既有索引流水线成为 active 版本
        Long textFileId =
                uploadKnowledgeText("scan-source.txt", "source text: contract amount 290.00 CNY\n原始文本：合同金额 290.00 元。");
        AiKnowledgeIngestionResultDTO firstIngestion = ingest(textFileId, "scan-0001", sha256("原始文本"));
        ingestionJob.execute(null);
        Long firstVersionId = firstIngestion.getVersionId();
        assertThat(documentService.getVersion(firstVersionId).getStatus())
                .isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_READY);
        assertThat(documentService
                        .getActiveVersion(firstIngestion.getDocumentId())
                        .getId())
                .isEqualTo(firstVersionId);

        UploadedImage pageImage = uploadChatImage("scan-page-1.png");

        // 2) OCR 失败（上游没有文本）：整笔拒绝，不产生版本、不替换旧版本
        VisionOcrTestConfiguration.FAIL_OCR.set(true);
        assertCode(
                () -> documentOcrService.recognizeAndReindex(new AiDocumentOcrRequestDTO()
                        .setDocumentId(firstIngestion.getDocumentId())
                        .setEndpointId(visionEndpointId)
                        .setPageImages(List.of(imageRef(pageImage)))),
                AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY);
        assertThat(documentService
                        .getActiveVersion(firstIngestion.getDocumentId())
                        .getId())
                .as("OCR 失败不得替换旧 active 版本（AT-024）")
                .isEqualTo(firstVersionId);
        assertThat(documentService.listVersions(firstIngestion.getDocumentId())).hasSize(1);

        // 3) OCR 成功：识别稿作为新版本重新索引，沿用既有切片与 active 切换语义
        VisionOcrTestConfiguration.FAIL_OCR.set(false);
        EmbeddingTestConfiguration.WRITTEN.clear();
        AiOcrDocumentReindexResultDTO reindexed = documentOcrService.recognizeAndReindex(new AiDocumentOcrRequestDTO()
                .setDocumentId(firstIngestion.getDocumentId())
                .setEndpointId(visionEndpointId)
                .setPageImages(List.of(imageRef(pageImage))));
        assertThat(reindexed.getVersionNo()).isEqualTo(2);
        assertThat(reindexed.isCreatedVersion()).isTrue();
        assertThat(reindexed.getSourceRef()).isEqualTo("ocr:sourceFileId=" + pageImage.fileId());
        assertThat(reindexed.getConfidenceSource()).isEqualTo(AiVisionConfidenceSource.UNKNOWN);
        assertThat(reindexed.isReviewRequired()).isTrue();
        assertThat(activeBindingForDerivedFile(reindexed.getDerivedFileId()))
                .as("派生识别稿必须是绑定该知识库的知识文档（A07 归属判定要过）")
                .isEqualTo(KNOWLEDGE_BASE_CODE);

        ingestionJob.execute(null);

        Long secondVersionId = reindexed.getVersionId();
        AiKnowledgeDocumentVersionDO secondVersion = documentService.getVersion(secondVersionId);
        assertThat(secondVersion.getStatus()).isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_READY);
        assertThat(secondVersion.getSourceRef()).isEqualTo("ocr:sourceFileId=" + pageImage.fileId());
        assertThat(secondVersion.getChunkCount()).isNotNull().isGreaterThan(0);
        assertThat(documentService
                        .getActiveVersion(firstIngestion.getDocumentId())
                        .getId())
                .as("索引成功后才切 active 到 OCR 版本")
                .isEqualTo(secondVersionId);
        assertThat(documentService.getVersion(firstVersionId).getStatus())
                .as("旧版本保留可追溯")
                .isEqualTo(AiKnowledgeDocumentVersionDO.STATUS_SUPERSEDED);

        // 识别稿真的进了切片：向量载荷里的正文带页码与"机器识别"说明（沿用既有切片器与位置语义）
        assertThat(EmbeddingTestConfiguration.WRITTEN).isNotEmpty();
        assertThat(EmbeddingTestConfiguration.WRITTEN).allSatisfy(document -> assertThat(
                        String.valueOf(document.payload().get("text")))
                .contains("第 1 页")
                .contains("机器识别")
                .contains(OCR_TEXT));
        assertThat(EmbeddingTestConfiguration.WRITTEN.get(0).payload().get("location_ref"))
                .as("位置来自既有解析器（.txt 走段落编号），不另造位置体系")
                .isEqualTo("段落 1");
    }

    private void prepareFileStorageAndApplication() {
        fileConfigId = fileConfigService.createFileConfig(
                new FileConfigDO()
                        .setName("it-ai-vision-" + System.nanoTime())
                        .setStorage(FileStorageEnum.DB.getStorage()),
                Map.of("domain", "http://localhost/files"));
        fileConfigService.updateFileConfigMaster(fileConfigId);
        AiApplicationCredentialIssueDTO issue = applicationService.createApplication(new AiApplicationSaveDTO()
                .setAppCode(APP_CODE)
                .setName("IT 视觉应用")
                .setOrigins(List.of("https://crm.example.com")));
        applicationService.updateStatus(issue.getApplication().getId(), 0, true);
        applicationSecret = issue.getSecret();
        subjectService.syncSubject(
                issue.getApplication().getId(), AiSubjectType.USER, "alice", "Alice", "crm-auth", 1L);
        // A03：alice 必须被显式授到知识库读权，否则票据签发的范围解析直接 DENY（与既有 IT 同口径）；
        // 每个用例前都会准备一次，因此授权创建必须是幂等的（重复 = 已存在）。
        ensureGrant(issue.getApplication().getId(), "USER", "alice", KNOWLEDGE_BASE_CODE);
        ensureGrant(issue.getApplication().getId(), "APP", null, KNOWLEDGE_BASE_CODE);
        subjectService.syncSubject(issue.getApplication().getId(), AiSubjectType.USER, "bob", "Bob", "crm-auth", 1L);
    }

    /** 幂等授权：同一应用在多次 @BeforeEach 之间不重复创建（已存在按成功处理）。 */
    private void ensureGrant(Long applicationId, String subjectType, String externalUserId, String resourceKey) {
        try {
            grantService.createGrant(
                    applicationId, subjectType, externalUserId, "KNOWLEDGE_BASE", resourceKey, Set.of("READ"));
        } catch (ServiceException exception) {
            // 已存在即视为准备完成；其它错误照旧抛出
            if (!String.valueOf(exception.getCode()).startsWith("1003")) {
                throw exception;
            }
        }
    }

    private void prepareKnowledgeBase() {
        knowledgeBaseId = baseService.create(new AiKnowledgeBaseSaveDTO()
                .setCode(KNOWLEDGE_BASE_CODE)
                .setName("IT 视觉知识库")
                .setVisibility(AiKnowledgeBaseDO.VISIBILITY_SHARED)
                .setEmbeddingModel("text-embedding-3-small")
                .setEmbeddingDimension(EMBEDDING_DIMENSION));
        AiKnowledgeBaseDO created = baseService.getKnowledgeBase(knowledgeBaseId);
        baseService.updateStatus(knowledgeBaseId, created.getVersion(), true);
        // 知识库读权已由 prepareFileStorageAndApplication 的 ensureGrant 幂等创建；
        // 这里不再重复创建（同一元组二次 createGrant 会抛"授权已存在"）。
        visionEndpointId = createEndpoint("it-vision-ocr", List.of("TEXT", "IMAGE_OCR"));
    }

    private Long currentApplicationId() {
        return jdbcTemplate.queryForObject("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
    }

    private Long createEndpoint(String name, List<String> capabilities) {
        AiModelEndpointSaveDTO saveDTO = new AiModelEndpointSaveDTO();
        saveDTO.setName(name);
        saveDTO.setProvider("openai_compatible");
        saveDTO.setBaseUrl("https://it-vision.example.com/v1");
        saveDTO.setModelId("it-vision-model");
        saveDTO.setCapabilities(capabilities);
        saveDTO.setCredential("sk-it-vision");
        Long endpointId = endpointService.createEndpoint(saveDTO);
        var created = endpointService.getEndpoint(endpointId);
        endpointService.updateEndpointStatus(endpointId, created.getVersion(), true);
        // 真实探测走替身端口：让"探测已确认"这一条准入判据真的成立（替身对声明能力返回 SUPPORTED）
        probeService.probeAll(endpointId);
        return endpointId;
    }

    private UploadedImage uploadChatImage(String fileName) {
        byte[] content = png(96, 96);
        Long fileId = fileService
                .upload("ai_chat_session", "session-alice", fileName, "image/png", content)
                .getFileId();
        return new UploadedImage(fileId, content.length);
    }

    private Long uploadKnowledgeText(String fileName, String text) {
        return fileService
                .upload(
                        "ai_knowledge_document",
                        KNOWLEDGE_BASE_CODE,
                        fileName,
                        "text/plain",
                        text.getBytes(StandardCharsets.UTF_8))
                .getFileId();
    }

    private AiKnowledgeIngestionResultDTO ingest(Long fileId, String sourceKey, String hash) {
        return ingestionService.ingest(new AiKnowledgeIngestionRequestDTO()
                .setKnowledgeBaseId(knowledgeBaseId)
                .setSourceKey(sourceKey)
                .setTitle("扫描合同")
                .setSourceType(AiKnowledgeDocumentDO.SOURCE_UPLOAD)
                .setFileId(fileId)
                .setContentHash(hash));
    }

    private String activeBindingForDerivedFile(Long derivedFileId) {
        return jdbcTemplate.queryForObject(
                "SELECT business_key FROM ai_file_binding WHERE file_id = ? AND business_type ="
                        + " 'ai_knowledge_document' AND status = 'ACTIVE'",
                String.class,
                derivedFileId);
    }

    private static AiVisionImageRefDTO imageRef(UploadedImage image) {
        return imageRef(image.fileId(), image.size());
    }

    private static AiVisionImageRefDTO imageRef(Long fileId, long size) {
        return new AiVisionImageRefDTO().setFileId(fileId).setMime("image/png").setSize(size);
    }

    private void loginAs(String externalUserId) {
        String ticket = ticketService
                .issue(APP_CODE, applicationSecret, AiSubjectType.USER, externalUserId, List.of(KNOWLEDGE_BASE_CODE))
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
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            throw new IllegalStateException("SHA-256 不可用", missingAlgorithm);
        }
    }

    private static byte[] png(int width, int height) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("测试图片编码失败", failure);
        }
    }

    /** 已上传的会话图片（编号 + 真实字节数：声明必须与内容一致）。 */
    private record UploadedImage(Long fileId, long size) {}
}
