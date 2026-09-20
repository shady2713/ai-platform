package com.basicframework.module.ai.controller.admin.query;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.module.ai.controller.admin.query.vo.AiQueryPlanReqVO;
import com.basicframework.module.ai.controller.admin.query.vo.AiQueryPlanRespVO;
import com.basicframework.module.ai.controller.admin.query.vo.AiQuerySummaryRespVO;
import com.basicframework.module.ai.service.query.planner.AiQueryPlanner;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanRequestDTO;
import com.basicframework.module.ai.service.query.planner.dto.AiQueryPlanResultDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 查询规划接口（D05）。
 *
 * <p>权限码与 V69 迁移的菜单种子一一对应：生成计划 {@code ai:query:plan}、查看摘要 {@code ai:query:summary}。
 *
 * <p>返回只有两种正常结果：已校验计划（带计划哈希）或澄清追问；模型输出 SQL 片段、
 * 引用未授权数据集、计划不合规都在服务层被拒（稳定错误码）。
 */
@Tag(name = "管理后台 - AI 查询规划")
@RestController
@RequestMapping("/ai/query")
@Validated
@RequiredArgsConstructor
public class AiQueryController {

    private final AiQueryPlanner queryPlanner;

    @PostMapping("/plan")
    @Operation(summary = "生成查询计划（或返回澄清追问；不接受 SQL）")
    @PreAuthorize("@ss.hasPermission('ai:query:plan')")
    public CommonResult<AiQueryPlanRespVO> plan(@Valid @RequestBody AiQueryPlanReqVO reqVO) {
        AiQueryPlanResultDTO result = queryPlanner.plan(new AiQueryPlanRequestDTO()
                .setDatasetId(reqVO.getDatasetId())
                .setDatasetVersionId(reqVO.getDatasetVersionId())
                .setEndpointId(reqVO.getEndpointId())
                .setQuestion(reqVO.getQuestion())
                .setAllowedFieldCodes(reqVO.getAllowedFieldCodes())
                .setMaxRepairs(reqVO.getMaxRepairs()));
        return success(toRespVO(result));
    }

    @GetMapping("/summary")
    @Operation(summary = "查看模型可见的数据集摘要（只含授权字段与语义元数据）")
    @PreAuthorize("@ss.hasPermission('ai:query:summary')")
    public CommonResult<AiQuerySummaryRespVO> summary(
            @Parameter(description = "数据集编号", required = true) @RequestParam("datasetId") @NotNull @Positive
                    Long datasetId,
            @Parameter(description = "数据集版本编号（缺省取最新已发布版本）")
                    @RequestParam(value = "datasetVersionId", required = false)
                    @Positive
                    Long datasetVersionId,
            @Parameter(description = "允许的字段/指标码（缺省为全部）") @RequestParam(value = "allowedFieldCodes", required = false)
                    List<String> allowedFieldCodes) {
        return success(new AiQuerySummaryRespVO()
                .setDatasetId(datasetId)
                .setSummaryJson(queryPlanner.datasetSummary(datasetId, datasetVersionId, allowedFieldCodes)));
    }

    private static AiQueryPlanRespVO toRespVO(AiQueryPlanResultDTO result) {
        return new AiQueryPlanRespVO()
                .setKind(result.getKind())
                .setDatasetId(result.getDatasetId())
                .setDatasetCode(result.getDatasetCode())
                .setPlanDatasetId(result.getPlanDatasetId())
                .setDatasetVersionId(result.getDatasetVersionId())
                .setDatasetVersionNo(result.getDatasetVersionNo())
                .setSchemaHash(result.getSchemaHash())
                .setPlanHash(result.getPlanHash())
                .setPlanJson(result.getPlanJson())
                .setQuestion(result.getQuestion())
                .setReason(result.getReason())
                .setCandidates(
                        result.getCandidates() == null
                                ? List.of()
                                : result.getCandidates().stream()
                                        .map(candidate -> new AiQueryPlanRespVO.Candidate()
                                                .setCode(candidate.code())
                                                .setLabel(candidate.label()))
                                        .toList())
                .setAttempts(result.getAttempts());
    }
}
