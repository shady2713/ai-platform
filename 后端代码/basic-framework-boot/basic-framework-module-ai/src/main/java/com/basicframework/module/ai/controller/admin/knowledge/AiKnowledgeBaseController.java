package com.basicframework.module.ai.controller.admin.knowledge;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeBasePageReqVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeBaseRespVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeBaseSaveReqVO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeBaseSaveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 知识库管理接口（K02）。
 *
 * <p>权限码与 V72 迁移的菜单种子一一对应：查询 {@code ai:knowledge:query}、新增 {@code ai:knowledge:create}、
 * 修改/启停 {@code ai:knowledge:update}、删除 {@code ai:knowledge:delete}；
 * 文档入库与版本查询在 {@link AiKnowledgeDocumentController}（{@code ai:knowledge:ingest} / {@code ai:knowledge:version}）。
 *
 * <p>响应不含文件内容、向量与任何凭据；"哪些应用能用这个库"由 A03 授权目录管理，
 * 本接口只暴露知识库自身的配置事实。
 */
@Tag(name = "管理后台 - AI 知识库")
@RestController
@RequestMapping("/ai/knowledge-base")
@Validated
@RequiredArgsConstructor
public class AiKnowledgeBaseController {

    private final AiKnowledgeBaseService knowledgeBaseService;

    @PostMapping("/create")
    @Operation(summary = "新增知识库（标识唯一；嵌入模型与维度创建后不可修改）")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:create')")
    public CommonResult<Long> create(@Valid @RequestBody AiKnowledgeBaseSaveReqVO reqVO) {
        return success(knowledgeBaseService.create(toSaveDTO(reqVO)));
    }

    @PutMapping("/update")
    @Operation(summary = "修改知识库（只允许名称/说明/管理者/保留策略）")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody AiKnowledgeBaseSaveReqVO reqVO) {
        knowledgeBaseService.update(toSaveDTO(reqVO));
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用知识库（停用后不接受新入库与索引换代）")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:update')")
    public CommonResult<Boolean> updateStatus(
            @Parameter(description = "知识库编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version,
            @Parameter(description = "是否启用", required = true) @RequestParam("enabled") @NotNull Boolean enabled) {
        knowledgeBaseService.updateStatus(id, version, enabled);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除知识库（被服务引用或仍有文档时拒绝）")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:delete')")
    public CommonResult<Boolean> delete(
            @Parameter(description = "知识库编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "乐观锁版本", required = true) @RequestParam("version") @NotNull @PositiveOrZero
                    Integer version) {
        knowledgeBaseService.delete(id, version);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "查询知识库")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<AiKnowledgeBaseRespVO> get(
            @Parameter(description = "知识库编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(knowledgeBaseService.getKnowledgeBase(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询知识库")
    @PreAuthorize("@ss.hasPermission('ai:knowledge:query')")
    public CommonResult<PageResult<AiKnowledgeBaseRespVO>> page(@Valid AiKnowledgeBasePageReqVO pageReqVO) {
        PageResult<AiKnowledgeBaseDO> page = knowledgeBaseService.getKnowledgeBasePage(
                pageReqVO, pageReqVO.getVisibility(), pageReqVO.getOwnerApplicationId(), pageReqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream().map(AiKnowledgeBaseController::toRespVO).toList(), page.getTotal()));
    }

    private static AiKnowledgeBaseSaveDTO toSaveDTO(AiKnowledgeBaseSaveReqVO reqVO) {
        return new AiKnowledgeBaseSaveDTO()
                .setId(reqVO.getId())
                .setCode(reqVO.getCode())
                .setName(reqVO.getName())
                .setDescription(reqVO.getDescription())
                .setVisibility(reqVO.getVisibility())
                .setOwnerApplicationId(reqVO.getOwnerApplicationId())
                .setManagerUserId(reqVO.getManagerUserId())
                .setEmbeddingModel(reqVO.getEmbeddingModel())
                .setEmbeddingDimension(reqVO.getEmbeddingDimension())
                .setRetentionDays(reqVO.getRetentionDays())
                .setVersion(reqVO.getVersion());
    }

    static AiKnowledgeBaseRespVO toRespVO(AiKnowledgeBaseDO knowledgeBase) {
        return new AiKnowledgeBaseRespVO()
                .setId(knowledgeBase.getId())
                .setCode(knowledgeBase.getCode())
                .setName(knowledgeBase.getName())
                .setDescription(knowledgeBase.getDescription())
                .setVisibility(knowledgeBase.getVisibility())
                .setOwnerApplicationId(knowledgeBase.getOwnerApplicationId())
                .setManagerUserId(knowledgeBase.getManagerUserId())
                .setEmbeddingModel(knowledgeBase.getEmbeddingModel())
                .setEmbeddingDimension(knowledgeBase.getEmbeddingDimension())
                .setActiveGenerationNo(knowledgeBase.getActiveGenerationNo())
                .setRetentionDays(knowledgeBase.getRetentionDays())
                .setStatus(knowledgeBase.getStatus())
                .setVersion(knowledgeBase.getVersion())
                .setCreateTime(knowledgeBase.getCreateTime());
    }
}
