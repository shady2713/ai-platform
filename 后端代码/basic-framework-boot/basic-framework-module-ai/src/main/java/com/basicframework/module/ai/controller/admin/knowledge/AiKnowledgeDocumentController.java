package com.basicframework.module.ai.controller.admin.knowledge;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeDocumentIngestReqVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeDocumentIngestRespVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeDocumentPageReqVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeDocumentRespVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeDocumentVersionPageReqVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeDocumentVersionRespVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeIndexGenerationRespVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeIngestionTaskPageReqVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeIngestionTaskRespVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeUploadRespVO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.service.file.AiFileBusinessType;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.file.dto.AiFileUploadResultDTO;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeIndexGenerationService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentSaveDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentUpsertResultDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionFilePolicy;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionService;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionTaskDO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionRequestDTO;
import com.basicframework.module.ai.service.knowledge.ingestion.dto.AiKnowledgeIngestionResultDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI 知识文档管理接口（K02）。
 *
 * <p>权限码：入库 {@code ai:knowledge:ingest}（没有该权限不能入库——卡片验收项）、
 * 查询 {@code ai:knowledge:query}、删除 {@code ai:knowledge:delete}、
 * 版本与索引代查询 {@code ai:knowledge:version}。
 *
 * <p>入库是幂等的：同一 sourceKey + 同指纹返回既有版本（{@code reused=true, createdVersion=false}）；
 * 指纹变化才产生新版本。索引代的新建/激活/退役由入库链路（K05/K07）调用，本接口只提供只读视图。
 */
@Tag(name = "管理后台 - AI 知识文档")
@RestController
@RequestMapping("/ai/knowledge-document")
@Validated
@RequiredArgsConstructor
public class AiKnowledgeDocumentController {

    private final AiKnowledgeDocumentService documentService;

    private final AiKnowledgeIndexGenerationService generationService;

    private final AiKnowledgeBaseService knowledgeBaseService;

    private final AiFileService fileService;

    private final AiKnowledgeIngestionService ingestionService;

    @PostMapping("/ingest")
    @Operation(summary = "文档入库（sourceKey 幂等：同指纹复用，指纹变化生成新版本）")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:ingest')")
    public CommonResult<AiKnowledgeDocumentIngestRespVO> ingest(
            @Valid @RequestBody AiKnowledgeDocumentIngestReqVO reqVO) {
        AiKnowledgeDocumentUpsertResultDTO result = documentService.upsert(new AiKnowledgeDocumentSaveDTO()
                .setKnowledgeBaseId(reqVO.getKnowledgeBaseId())
                .setSourceKey(reqVO.getSourceKey())
                .setTitle(reqVO.getTitle())
                .setSourceType(reqVO.getSourceType())
                .setSourceRef(reqVO.getSourceRef())
                .setFileId(reqVO.getFileId())
                .setContentHash(reqVO.getContentHash()));
        return success(new AiKnowledgeDocumentIngestRespVO()
                .setDocumentId(result.getDocumentId())
                .setVersionId(result.getVersionId())
                .setVersionNo(result.getVersionNo())
                .setReused(result.isReused())
                .setCreatedVersion(result.isCreatedVersion())
                .setDocumentStatus(result.getDocumentStatus()));
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除文档（标记删除中，索引与切片由后台回收）")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:delete')")
    public CommonResult<Boolean> delete(
            @Parameter(description = "文档编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version) {
        documentService.deleteDocument(id, version);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "查询文档")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<AiKnowledgeDocumentRespVO> get(
            @Parameter(description = "文档编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(documentService.getDocument(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询文档")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<PageResult<AiKnowledgeDocumentRespVO>> page(@Valid AiKnowledgeDocumentPageReqVO pageReqVO) {
        PageResult<AiKnowledgeDocumentDO> page =
                documentService.getDocumentPage(pageReqVO, pageReqVO.getKnowledgeBaseId(), pageReqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream()
                        .map(AiKnowledgeDocumentController::toRespVO)
                        .toList(),
                page.getTotal()));
    }

    @GetMapping("/version/get")
    @Operation(summary = "查询文档版本")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:version')")
    public CommonResult<AiKnowledgeDocumentVersionRespVO> getVersion(
            @Parameter(description = "版本编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(toVersionRespVO(documentService.getVersion(id)));
    }

    @GetMapping("/version/page")
    @Operation(summary = "分页查询文档版本（版本号倒序）")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:version')")
    public CommonResult<PageResult<AiKnowledgeDocumentVersionRespVO>> versionPage(
            @Valid AiKnowledgeDocumentVersionPageReqVO pageReqVO) {
        PageResult<AiKnowledgeDocumentVersionDO> page =
                documentService.getVersionPage(pageReqVO, pageReqVO.getDocumentId());
        return success(new PageResult<>(
                page.getList().stream()
                        .map(AiKnowledgeDocumentController::toVersionRespVO)
                        .toList(),
                page.getTotal()));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传并入库文档（校验类型/大小/归属后创建版本与入库任务）")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:ingest')")
    public CommonResult<AiKnowledgeUploadRespVO> upload(
            @Parameter(description = "知识库编号", required = true) @RequestParam("knowledgeBaseId") @NotNull @Positive
                    Long knowledgeBaseId,
            @Parameter(description = "来源幂等键", required = true) @RequestParam("sourceKey") @NotEmpty @Size(max = 128)
                    String sourceKey,
            @Parameter(description = "文档标题", required = true) @RequestParam("title") @NotEmpty @Size(max = 256)
                    String title,
            @Parameter(description = "来源位置") @RequestParam(value = "sourceRef", required = false) @Size(max = 512)
                    String sourceRef,
            @Parameter(description = "文件", required = true) @RequestParam("file") MultipartFile file) {
        String fileName = AiKnowledgeIngestionFilePolicy.requireSupportedFile(
                file.getOriginalFilename(), file.getSize(), new byte[] {1});
        AiKnowledgeIngestionFilePolicy.requireCompatibleContentType(file.getContentType());
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException unreadable) {
            // 读取失败按文件不合法处理：不回显底层异常文本
            throw com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception(
                    com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_FILE_INVALID);
        }
        AiKnowledgeIngestionFilePolicy.requireSupportedFile(fileName, content.length, content);
        String knowledgeBaseCode =
                knowledgeBaseService.getKnowledgeBase(knowledgeBaseId).getCode();
        AiFileUploadResultDTO uploaded = fileService.upload(
                AiFileBusinessType.KNOWLEDGE_DOCUMENT.code(),
                knowledgeBaseCode,
                fileName,
                file.getContentType(),
                content);
        AiKnowledgeIngestionResultDTO result = ingestionService.ingest(new AiKnowledgeIngestionRequestDTO()
                .setKnowledgeBaseId(knowledgeBaseId)
                .setSourceKey(sourceKey)
                .setTitle(title)
                .setSourceType(AiKnowledgeDocumentDO.SOURCE_UPLOAD)
                .setSourceRef(sourceRef)
                .setFileId(uploaded.getFileId())
                .setContentHash(AiKnowledgeIngestionFilePolicy.sha256(content)));
        return success(new AiKnowledgeUploadRespVO()
                .setDocumentId(result.getDocumentId())
                .setVersionId(result.getVersionId())
                .setVersionNo(result.getVersionNo())
                .setTaskId(result.getTaskId())
                .setReused(result.isReused())
                .setCreatedVersion(result.isCreatedVersion()));
    }

    @GetMapping("/task/get")
    @Operation(summary = "查询入库任务")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<AiKnowledgeIngestionTaskRespVO> getTask(
            @Parameter(description = "任务编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(toTaskRespVO(ingestionService.getTask(id)));
    }

    @GetMapping("/task/page")
    @Operation(summary = "分页查询入库任务")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<PageResult<AiKnowledgeIngestionTaskRespVO>> taskPage(
            @Valid AiKnowledgeIngestionTaskPageReqVO pageReqVO) {
        PageResult<AiKnowledgeIngestionTaskDO> page =
                ingestionService.getTaskPage(pageReqVO, pageReqVO.getKnowledgeBaseId(), pageReqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream()
                        .map(AiKnowledgeDocumentController::toTaskRespVO)
                        .toList(),
                page.getTotal()));
    }

    @PostMapping("/task/retry")
    @Operation(summary = "人工重试入库任务（仅失败/结果未知的任务）")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:ingest')")
    public CommonResult<Boolean> retryTask(
            @Parameter(description = "任务编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version) {
        ingestionService.retry(id, version);
        return success(true);
    }

    @GetMapping("/generation/list")
    @Operation(summary = "列出知识库的索引代（代序号倒序）")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:version')")
    public CommonResult<List<AiKnowledgeIndexGenerationRespVO>> generationList(
            @Parameter(description = "知识库编号", required = true) @RequestParam("knowledgeBaseId") @NotNull @Positive
                    Long knowledgeBaseId) {
        return success(generationService.listGenerations(knowledgeBaseId).stream()
                .map(AiKnowledgeDocumentController::toGenerationRespVO)
                .toList());
    }

    private static AiKnowledgeDocumentRespVO toRespVO(AiKnowledgeDocumentDO document) {
        return new AiKnowledgeDocumentRespVO()
                .setId(document.getId())
                .setKnowledgeBaseId(document.getKnowledgeBaseId())
                .setSourceKey(document.getSourceKey())
                .setTitle(document.getTitle())
                .setSourceType(document.getSourceType())
                .setSourceRef(document.getSourceRef())
                .setStatus(document.getStatus())
                .setActiveVersionNo(document.getActiveVersionNo())
                .setLatestVersionNo(document.getLatestVersionNo())
                .setFailureReason(document.getFailureReason())
                .setParseNote(document.getParseNote())
                .setVersion(document.getVersion())
                .setCreateTime(document.getCreateTime());
    }

    private static AiKnowledgeDocumentVersionRespVO toVersionRespVO(AiKnowledgeDocumentVersionDO version) {
        return new AiKnowledgeDocumentVersionRespVO()
                .setId(version.getId())
                .setDocumentId(version.getDocumentId())
                .setVersionNo(version.getVersionNo())
                .setFileId(version.getFileId())
                .setContentHash(version.getContentHash())
                .setSourceRef(version.getSourceRef())
                .setStatus(version.getStatus())
                .setIndexGeneration(version.getIndexGeneration())
                .setChunkCount(version.getChunkCount())
                .setFailureReason(version.getFailureReason())
                .setReadyAt(version.getReadyAt())
                .setVersion(version.getVersion())
                .setCreateTime(version.getCreateTime());
    }

    private static AiKnowledgeIngestionTaskRespVO toTaskRespVO(AiKnowledgeIngestionTaskDO task) {
        return new AiKnowledgeIngestionTaskRespVO()
                .setId(task.getId())
                .setKnowledgeBaseId(task.getKnowledgeBaseId())
                .setDocumentId(task.getDocumentId())
                .setDocumentVersionId(task.getDocumentVersionId())
                .setTaskKind(task.getTaskKind())
                .setStatus(task.getStatus())
                .setAttemptCount(task.getAttemptCount())
                .setMaxAttempts(task.getMaxAttempts())
                .setNextAttemptTime(task.getNextAttemptTime())
                .setLastErrorCode(task.getLastErrorCode())
                .setVersion(task.getVersion())
                .setCreateTime(task.getCreateTime());
    }

    private static AiKnowledgeIndexGenerationRespVO toGenerationRespVO(AiKnowledgeIndexGenerationDO generation) {
        return new AiKnowledgeIndexGenerationRespVO()
                .setId(generation.getId())
                .setKnowledgeBaseId(generation.getKnowledgeBaseId())
                .setGenerationNo(generation.getGenerationNo())
                .setEmbeddingModel(generation.getEmbeddingModel())
                .setDimension(generation.getDimension())
                .setCollectionName(generation.getCollectionName())
                .setStatus(generation.getStatus())
                .setChunkCount(generation.getChunkCount())
                .setDocumentCount(generation.getDocumentCount())
                .setFailureReason(generation.getFailureReason())
                .setActivatedAt(generation.getActivatedAt())
                .setRetiredAt(generation.getRetiredAt());
    }
}
