package com.basicframework.module.ai.controller.admin.evaluation;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalCaseRespVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalCaseSaveReqVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalResultPageReqVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalResultRespVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalReviewReqVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalRunPageReqVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalRunRespVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalRunStartReqVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalSuitePageReqVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalSuiteRespVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalSuiteSaveReqVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalSuiteUpdateReqVO;
import com.basicframework.module.ai.controller.admin.evaluation.vo.AiEvalVersionActionReqVO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalCaseDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalResultDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalRunDO;
import com.basicframework.module.ai.dal.dataobject.evaluation.AiEvalSuiteDO;
import com.basicframework.module.ai.service.evaluation.AiEvalRunService;
import com.basicframework.module.ai.service.evaluation.AiEvalSuiteService;
import com.basicframework.module.ai.service.evaluation.dto.AiEvalCaseSaveDTO;
import com.basicframework.module.ai.service.evaluation.dto.AiEvalSuiteSaveDTO;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 评测控制面接口（Q04）。
 *
 * <p>四类权限分开鉴权：查看 {@code ai:eval:query}、维护套件与样例 {@code ai:eval:manage}、
 * 执行评测 {@code ai:eval:run}、人工复核 {@code ai:eval:review}。查看接口只回配置、判定与摘要，
 * 不回提示词/响应正文与任何凭据（AT-011/058）。
 *
 * <p>执行走与真实运行相同的运行服务与授权（套件登记的应用 + 合成主体），
 * 判定由确定性规则给出（金额/日期/引用/结构/版本/无秘密），模型评分不参与。
 */
@Tag(name = "管理后台 - AI 评测")
@RestController
@RequestMapping("/ai/eval")
@Validated
@RequiredArgsConstructor
public class AiEvalController {

    private final AiEvalSuiteService suiteService;

    private final AiEvalRunService runService;

    @PostMapping("/suite/create")
    @Operation(summary = "创建评测套件（同一应用下标识唯一；样例只允许 L1/L2 分级）")
    @PreAuthorize("@ss.hasPermission('ai:eval:manage')")
    public CommonResult<Long> createSuite(@Valid @RequestBody AiEvalSuiteSaveReqVO reqVO) {
        return success(suiteService.createSuite(new AiEvalSuiteSaveDTO()
                .setApplicationId(reqVO.getApplicationId())
                .setCode(reqVO.getCode())
                .setName(reqVO.getName())
                .setDescription(reqVO.getDescription())
                .setServiceId(reqVO.getServiceId())
                .setSubjectType(reqVO.getSubjectType())
                .setExternalUserId(reqVO.getExternalUserId())
                .setDataLevel(reqVO.getDataLevel())));
    }

    @PutMapping("/suite/update")
    @Operation(summary = "更新评测套件（仅草稿；标识与服务不可修改）")
    @PreAuthorize("@ss.hasPermission('ai:eval:manage')")
    public CommonResult<Boolean> updateSuite(@Valid @RequestBody AiEvalSuiteUpdateReqVO reqVO) {
        suiteService.updateSuite(new AiEvalSuiteSaveDTO()
                .setId(reqVO.getId())
                .setVersion(reqVO.getVersion())
                .setName(reqVO.getName())
                .setDescription(reqVO.getDescription())
                .setDataLevel(reqVO.getDataLevel()));
        return success(true);
    }

    @PostMapping("/suite/freeze")
    @Operation(summary = "冻结套件（校验期望规则并记录内容摘要；冻结后编辑请先建新修订）")
    @PreAuthorize("@ss.hasPermission('ai:eval:manage')")
    public CommonResult<Boolean> freezeSuite(@Valid @RequestBody AiEvalVersionActionReqVO reqVO) {
        suiteService.freezeSuite(reqVO.getId(), reqVO.getVersion());
        return success(true);
    }

    @PostMapping("/suite/new-revision")
    @Operation(summary = "冻结后创建新修订（回到草稿；历史运行的摘要不变）")
    @PreAuthorize("@ss.hasPermission('ai:eval:manage')")
    public CommonResult<Boolean> newRevision(@Valid @RequestBody AiEvalVersionActionReqVO reqVO) {
        suiteService.newRevision(reqVO.getId(), reqVO.getVersion());
        return success(true);
    }

    @GetMapping("/suite/get")
    @Operation(summary = "读取评测套件")
    @Parameter(name = "id", description = "套件编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:eval:query')")
    public CommonResult<AiEvalSuiteRespVO> getSuite(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toSuiteRespVO(suiteService.requireSuite(id)));
    }

    @GetMapping("/suite/page")
    @Operation(summary = "评测套件分页（可按应用与状态过滤）")
    @PreAuthorize("@ss.hasPermission('ai:eval:query')")
    public CommonResult<PageResult<AiEvalSuiteRespVO>> pageSuites(@Valid AiEvalSuitePageReqVO reqVO) {
        PageResult<AiEvalSuiteDO> page = suiteService.pageSuites(reqVO, reqVO.getApplicationId(), reqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream().map(AiEvalController::toSuiteRespVO).toList(), page.getTotal()));
    }

    @PostMapping("/case/create")
    @Operation(summary = "新增评测样例（仅草稿套件；期望规则入库即校验）")
    @PreAuthorize("@ss.hasPermission('ai:eval:manage')")
    public CommonResult<Long> createCase(@Valid @RequestBody AiEvalCaseSaveReqVO reqVO) {
        return success(suiteService.createCase(toCaseDTO(reqVO)));
    }

    @PutMapping("/case/update")
    @Operation(summary = "更新评测样例（仅草稿套件）")
    @PreAuthorize("@ss.hasPermission('ai:eval:manage')")
    public CommonResult<Boolean> updateCase(@Valid @RequestBody AiEvalCaseSaveReqVO reqVO) {
        suiteService.updateCase(toCaseDTO(reqVO));
        return success(true);
    }

    @DeleteMapping("/case/delete")
    @Operation(summary = "删除评测样例（仅草稿套件；软删除，历史运行保留快照）")
    @PreAuthorize("@ss.hasPermission('ai:eval:manage')")
    public CommonResult<Boolean> deleteCase(@Valid @RequestBody AiEvalVersionActionReqVO reqVO) {
        suiteService.deleteCase(reqVO.getId(), reqVO.getVersion());
        return success(true);
    }

    @GetMapping("/case/list")
    @Operation(summary = "套件内样例（按标识升序：执行与报告顺序稳定）")
    @Parameter(name = "suiteId", description = "套件编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:eval:query')")
    public CommonResult<List<AiEvalCaseRespVO>> listCases(@RequestParam("suiteId") @NotNull @Positive Long suiteId) {
        return success(suiteService.listCases(suiteId).stream()
                .map(AiEvalController::toCaseRespVO)
                .toList());
    }

    @PostMapping("/run/start")
    @Operation(summary = "开始评测（冻结快照 → 逐例走同一运行服务执行 → 确定性核验）")
    @PreAuthorize("@ss.hasPermission('ai:eval:run')")
    public CommonResult<Long> startRun(@Valid @RequestBody AiEvalRunStartReqVO reqVO) {
        return success(runService.startRun(reqVO.getSuiteId()));
    }

    @GetMapping("/run/get")
    @Operation(summary = "读取评测运行（含冻结的套件摘要与计数）")
    @Parameter(name = "id", description = "评测运行编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:eval:query')")
    public CommonResult<AiEvalRunRespVO> getRun(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toRunRespVO(runService.requireRun(id)));
    }

    @GetMapping("/run/page")
    @Operation(summary = "评测运行分页（可按套件过滤）")
    @PreAuthorize("@ss.hasPermission('ai:eval:query')")
    public CommonResult<PageResult<AiEvalRunRespVO>> pageRuns(@Valid AiEvalRunPageReqVO reqVO) {
        PageResult<AiEvalRunDO> page = runService.pageRuns(reqVO, reqVO.getSuiteId());
        return success(new PageResult<>(
                page.getList().stream().map(AiEvalController::toRunRespVO).toList(), page.getTotal()));
    }

    @GetMapping("/result/list")
    @Operation(summary = "运行内逐例结果（按冻结顺序）")
    @Parameter(name = "runId", description = "评测运行编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:eval:query')")
    public CommonResult<List<AiEvalResultRespVO>> listResults(@RequestParam("runId") @NotNull @Positive Long runId) {
        return success(runService.listResults(runId).stream()
                .map(AiEvalController::toResultRespVO)
                .toList());
    }

    @GetMapping("/result/page")
    @Operation(summary = "评测结果分页（可按判定过滤）")
    @PreAuthorize("@ss.hasPermission('ai:eval:query')")
    public CommonResult<PageResult<AiEvalResultRespVO>> pageResults(@Valid AiEvalResultPageReqVO reqVO) {
        PageResult<AiEvalResultDO> page = runService.pageResults(reqVO, reqVO.getRunId(), reqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream().map(AiEvalController::toResultRespVO).toList(), page.getTotal()));
    }

    @GetMapping("/report")
    @Operation(summary = "可复现报告（运行快照 + 逐例判定与摘要；不含提示词与响应正文）")
    @Parameter(name = "runId", description = "评测运行编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:eval:query')")
    public CommonResult<String> report(@RequestParam("runId") @NotNull @Positive Long runId) {
        return success(runService.report(runId));
    }

    @PostMapping("/result/review")
    @Operation(summary = "人工复核（只对等待复核的结果有效；复核改变最终判定与结果摘要）")
    @PreAuthorize("@ss.hasPermission('ai:eval:review')")
    public CommonResult<Boolean> review(@Valid @RequestBody AiEvalReviewReqVO reqVO) {
        runService.review(reqVO.getResultId(), Boolean.TRUE.equals(reqVO.getApprove()), reqVO.getNote());
        return success(true);
    }

    private static AiEvalCaseSaveDTO toCaseDTO(AiEvalCaseSaveReqVO reqVO) {
        return new AiEvalCaseSaveDTO()
                .setId(reqVO.getId())
                .setVersion(reqVO.getVersion())
                .setSuiteId(reqVO.getSuiteId())
                .setCaseKey(reqVO.getCaseKey())
                .setTitle(reqVO.getTitle())
                .setSeverity(reqVO.getSeverity())
                .setQuestion(reqVO.getQuestion())
                .setExpectVersion(reqVO.getExpectVersion())
                .setChecksJson(reqVO.getChecksJson())
                .setNeedsReview(reqVO.getNeedsReview());
    }

    private static AiEvalSuiteRespVO toSuiteRespVO(AiEvalSuiteDO entity) {
        return new AiEvalSuiteRespVO()
                .setId(entity.getId())
                .setApplicationId(entity.getApplicationId())
                .setCode(entity.getCode())
                .setName(entity.getName())
                .setDescription(entity.getDescription())
                .setServiceId(entity.getServiceId())
                .setSubjectType(entity.getSubjectType())
                .setExternalUserId(entity.getExternalUserId())
                .setDataLevel(entity.getDataLevel())
                .setStatus(entity.getStatus())
                .setRevision(entity.getRevision())
                .setCaseCount(entity.getCaseCount())
                .setFrozenTime(entity.getFrozenTime())
                .setContentDigest(entity.getContentDigest())
                .setVersion(entity.getVersion())
                .setCreateTime(entity.getCreateTime());
    }

    private static AiEvalCaseRespVO toCaseRespVO(AiEvalCaseDO entity) {
        return new AiEvalCaseRespVO()
                .setId(entity.getId())
                .setSuiteId(entity.getSuiteId())
                .setCaseKey(entity.getCaseKey())
                .setTitle(entity.getTitle())
                .setSeverity(entity.getSeverity())
                .setQuestion(entity.getQuestion())
                .setExpectVersion(entity.getExpectVersion())
                .setChecksJson(entity.getChecksJson())
                .setNeedsReview(entity.getNeedsReview())
                .setVersion(entity.getVersion());
    }

    private static AiEvalRunRespVO toRunRespVO(AiEvalRunDO entity) {
        return new AiEvalRunRespVO()
                .setId(entity.getId())
                .setSuiteId(entity.getSuiteId())
                .setApplicationId(entity.getApplicationId())
                .setServiceId(entity.getServiceId())
                .setSuiteRevision(entity.getSuiteRevision())
                .setSuiteDigest(entity.getSuiteDigest())
                .setStatus(entity.getStatus())
                .setCaseTotal(entity.getCaseTotal())
                .setPassedCount(entity.getPassedCount())
                .setFailedCount(entity.getFailedCount())
                .setErrorCount(entity.getErrorCount())
                .setSummaryJson(entity.getSummaryJson())
                .setStartedTime(entity.getStartedTime())
                .setFinishedTime(entity.getFinishedTime());
    }

    private static AiEvalResultRespVO toResultRespVO(AiEvalResultDO entity) {
        return new AiEvalResultRespVO()
                .setId(entity.getId())
                .setRunId(entity.getRunId())
                .setCaseId(entity.getCaseId())
                .setCaseKey(entity.getCaseKey())
                .setSeverity(entity.getSeverity())
                .setStatus(entity.getStatus())
                .setExpectVersion(entity.getExpectVersion())
                .setObservedVersion(entity.getObservedVersion())
                .setRunRef(entity.getRunRef())
                .setVerdictJson(entity.getVerdictJson())
                .setFailureCode(entity.getFailureCode())
                .setCaseDigest(entity.getCaseDigest())
                .setResultDigest(entity.getResultDigest())
                .setReviewStatus(entity.getReviewStatus())
                .setReviewNote(entity.getReviewNote())
                .setReviewedBy(entity.getReviewedBy())
                .setReviewedTime(entity.getReviewedTime());
    }
}
