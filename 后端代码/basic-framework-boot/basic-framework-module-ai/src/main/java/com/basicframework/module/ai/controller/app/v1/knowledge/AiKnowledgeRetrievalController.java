package com.basicframework.module.ai.controller.app.v1.knowledge;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.knowledge.vo.AiKnowledgeSearchReqVO;
import com.basicframework.module.ai.controller.app.v1.knowledge.vo.AiKnowledgeSearchRespVO;
import com.basicframework.module.ai.service.knowledge.retrieval.AiKnowledgeRetrievalService;
import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeCitationDTO;
import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeRetrievalResultDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 应用端知识检索与引用读取（K06）。
 *
 * <p>三个端点都只要求登录（{@code @AuthenticatedOnly}），归属与可见性由**服务端**判定：
 * 授权范围来自当前主体的 A03 授权目录，引用来自本次检索候选，读取片段/原文再次鉴权
 * （A03 + A07 的业务文件权限 SPI）。调用方无法通过参数扩大可见范围。
 */
@Tag(name = "AI 应用端 - 知识检索与引用")
@RestController
@RequestMapping("/ai/knowledge")
@Validated
@RequiredArgsConstructor
public class AiKnowledgeRetrievalController {

    private final AiKnowledgeRetrievalService retrievalService;

    @PostMapping("/search")
    @Operation(summary = "知识检索（过滤条件由服务端按当前授权生成；无证据时明确返回空）")
    @AuthenticatedOnly
    public CommonResult<AiKnowledgeSearchRespVO> search(@Valid @RequestBody AiKnowledgeSearchReqVO reqVO) {
        AiKnowledgeRetrievalResultDTO result = retrievalService.search(reqVO.getQuery(), reqVO.getTopK());
        return success(new AiKnowledgeSearchRespVO()
                .setCitations(result.getCitations().stream()
                        .map(AiKnowledgeRetrievalController::toCitation)
                        .toList())
                .setCandidateCount(result.getCandidateCount())
                .setFilteredOutCount(result.getFilteredOutCount())
                .setSearchedKnowledgeBaseCount(result.getSearchedKnowledgeBaseCount())
                .setNoEvidence(result.noEvidence()));
    }

    @GetMapping("/citation")
    @Operation(summary = "读取引用片段（重新鉴权后按引用位置从原文取回）")
    @AuthenticatedOnly
    public CommonResult<String> citation(
            @Parameter(description = "引用标识", required = true) @RequestParam("citationId") @NotEmpty String citationId) {
        return success(retrievalService.readCitationSnippet(citationId));
    }

    @GetMapping("/document/content")
    @Operation(summary = "读取文档原文（重新鉴权后经业务文件权限 SPI 读取）")
    @AuthenticatedOnly
    public ResponseEntity<byte[]> content(
            @Parameter(description = "文档编号", required = true) @RequestParam("documentId") @NotNull @Positive
                    Long documentId) {
        byte[] content = retrievalService.readOriginal(documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"document-\" + documentId + \".bin\"")
                .body(content);
    }

    private static AiKnowledgeSearchRespVO.Citation toCitation(AiKnowledgeCitationDTO citation) {
        return new AiKnowledgeSearchRespVO.Citation()
                .setCitationId(citation.getCitationId())
                .setTitle(citation.getTitle())
                .setVersionNo(citation.getVersionNo())
                .setChunkIndex(citation.getChunkIndex())
                .setLocationRef(citation.getLocationRef())
                .setSnippet(citation.getSnippet());
    }

    /** 便于测试：正文按 UTF-8 解码。 */
    static String decode(byte[] content) {
        return content == null ? "" : new String(content, StandardCharsets.UTF_8);
    }
}
