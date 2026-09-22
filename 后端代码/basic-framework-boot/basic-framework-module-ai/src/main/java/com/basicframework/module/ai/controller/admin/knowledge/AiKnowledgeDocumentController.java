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
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeIndexGenerationService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentSaveDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentUpsertResultDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
