package com.basicframework.module.ai.controller.app.v1.report;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportPageReqVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportRefreshReqVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportRefreshRespVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportRefreshStateRespVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportRespVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportReviseReqVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportRevisionRespVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportSaveReqVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportVersionBriefVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportVersionRespVO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportVersionDO;
import com.basicframework.module.ai.service.report.persistence.AiReportService;
import com.basicframework.module.ai.service.report.persistence.dto.AiReportSaveDTO;
import com.basicframework.module.ai.service.report.refresh.AiReportRefreshService;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshRequestDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshResultDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshStateDTO;
import com.basicframework.module.ai.service.report.revision.AiReportRevisionDiff;
import com.basicframework.module.ai.service.report.revision.AiReportRevisionService;
import com.basicframework.module.ai.service.report.revision.dto.AiReportRevisionRequestDTO;
import com.basicframework.module.ai.service.report.revision.dto.AiReportRevisionResultDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 应用端报表保存、读取与对话修改（R04/R05）。
 *
 * <p>所有端点都只要求登录（{@code @AuthenticatedOnly}）：归属（应用 + 主体类型 + 外部用户标识）
 * 与授权范围都来自**服务端会话与 A03 判定**，请求体不提供任何归属或指纹字段。读取（含旧版本编号）
 * 每次都复核保存时的范围指纹，范围收窄后拒绝展示并要求按当前权限重新生成（第 4/5 步）。
 *
 * <p>对话修改（{@code /revise}）同样不提供归属与行范围：展示类修改复用保存时的数据（不查库），
 * 数据类修改必须走受控查询，行范围只能来自授权层——请求体给不出行范围，因此也绕不过受控查询。
 */
@Tag(name = "AI 应用端 - 报表保存与版本")
@RestController
@RequestMapping("/ai/report")
@Validated
@RequiredArgsConstructor
public class AiReportController {

    private final AiReportService reportService;

    private final AiReportRevisionService revisionService;

    private final AiReportRefreshService refreshService;

    @PostMapping("/save")
    @Operation(summary = "保存报表（不带编号为新建；带编号与乐观锁版本为保存新版本）")
    @AuthenticatedOnly
    public CommonResult<Long> save(@Valid @RequestBody AiReportSaveReqVO reqVO) {
        AiReportSaveDTO saveDTO = toSaveDTO(reqVO);
        return success(reqVO.getId() == null ? reportService.create(saveDTO) : reportService.saveVersion(saveDTO));
    }

    @PostMapping("/revise")
    @Operation(summary = "对话修改报表（展示类复用保存时的数据；数据类按当前权限重新查询；成功即新增版本）")
    @AuthenticatedOnly
    public CommonResult<AiReportRevisionRespVO> revise(@Valid @RequestBody AiReportReviseReqVO reqVO) {
        AiReportRevisionResultDTO result = revisionService.revise(new AiReportRevisionRequestDTO()
                .setReportId(reqVO.getId())
                .setBaseVersionNo(reqVO.getBaseVersionNo())
                .setVersion(reqVO.getVersion())
                .setInstruction(reqVO.getInstruction())
                .setEndpointId(reqVO.getEndpointId())
                .setDatasetId(reqVO.getDatasetId())
                .setDatasetVersionId(reqVO.getDatasetVersionId())
                .setCreatedByRun(reqVO.getCreatedByRun()));
        return success(toRevisionRespVO(result));
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新可刷新报表（按当前权限重放固定查询版本；失败保留旧结果并留痕）")
    @AuthenticatedOnly
    public CommonResult<AiReportRefreshRespVO> refresh(@Valid @RequestBody AiReportRefreshReqVO reqVO) {
        AiReportRefreshResultDTO result = refreshService.refresh(
                new AiReportRefreshRequestDTO().setReportId(reqVO.getId()).setCreatedByRun(reqVO.getCreatedByRun()));
        return success(toRefreshRespVO(result));
    }

    @GetMapping("/refresh/last")
    @Operation(summary = "查询上次刷新状态与上一次结果（读取结果前按当前权限复核）")
    @AuthenticatedOnly
    public CommonResult<AiReportRefreshStateRespVO> refreshLast(
            @Parameter(description = "报表编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(toRefreshStateRespVO(refreshService.lastState(id)));
    }

    @GetMapping("/get")
    @Operation(summary = "查询报表元信息（越权与不存在同语义）")
    @AuthenticatedOnly
    public CommonResult<AiReportRespVO> get(
            @Parameter(description = "报表编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(reportService.getReport(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询当前主体的报表")
    @AuthenticatedOnly
    public CommonResult<PageResult<AiReportRespVO>> page(@Valid AiReportPageReqVO reqVO) {
        PageResult<AiReportDO> page = reportService.getReportPage(reqVO, reqVO.getMode());
        return success(new PageResult<>(
                page.getList().stream().map(AiReportController::toRespVO).toList(), page.getTotal()));
    }

    @GetMapping("/versions")
    @Operation(summary = "查询报表版本列表（不含规格与数据正文）")
    @AuthenticatedOnly
    public CommonResult<List<AiReportVersionBriefVO>> versions(
            @Parameter(description = "报表编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(reportService.listVersions(id).stream()
                .map(AiReportController::toBriefVO)
                .toList());
    }

    @GetMapping("/current")
    @Operation(summary = "读取当前生效版本（复核保存时的授权范围，范围变化则拒绝展示）")
    @AuthenticatedOnly
    public CommonResult<AiReportVersionRespVO> current(
            @Parameter(description = "报表编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(toVersionRespVO(reportService.readCurrent(id)));
    }

    @GetMapping("/version")
    @Operation(summary = "读取指定版本（旧版本同样复核授权范围，复制编号不能绕过）")
    @AuthenticatedOnly
    public CommonResult<AiReportVersionRespVO> version(
            @Parameter(description = "报表编号", required = true) @RequestParam("id") @NotNull @Positive Long id,
            @Parameter(description = "版本号", required = true) @RequestParam("versionNo") @NotNull @Positive
                    Integer versionNo) {
        return success(toVersionRespVO(reportService.getVersion(id, versionNo)));
    }

    private static AiReportRefreshRespVO toRefreshRespVO(AiReportRefreshResultDTO result) {
        return new AiReportRefreshRespVO()
                .setStatus(result.getStatus())
                .setReportId(result.getReportId())
                .setBaseVersionNo(result.getBaseVersionNo())
                .setResultVersionNo(result.getResultVersionNo())
                .setAsOf(result.getAsOf())
                .setCompleteness(result.getCompleteness())
                .setReason(result.getReason())
                .setDataJson(result.getDataJson())
                .setNote(result.getNote());
    }

    private static AiReportRefreshStateRespVO toRefreshStateRespVO(AiReportRefreshStateDTO state) {
        return new AiReportRefreshStateRespVO()
                .setReportId(state.getReportId())
                .setAttempted(state.isAttempted())
                .setStatus(state.getStatus())
                .setAsOf(state.getAsOf())
                .setReason(state.getReason())
                .setCompleteness(state.getCompleteness())
                .setResultVersionNo(state.getResultVersionNo())
                .setDataJson(state.getDataJson());
    }

    /** 修订结果 → 协议层 VO（差异与澄清候选逐项映射，不新增字段语义）。 */
    private static AiReportRevisionRespVO toRevisionRespVO(AiReportRevisionResultDTO result) {
        AiReportRevisionRespVO respVO = new AiReportRevisionRespVO()
                .setOutcome(result.getOutcome())
                .setReportId(result.getReportId())
                .setBaseVersionNo(result.getBaseVersionNo())
                .setNewVersionNo(result.getNewVersionNo())
                .setQueryPerformed(result.isQueryPerformed())
                .setClarificationQuestion(result.getClarificationQuestion())
                .setClarificationReason(result.getClarificationReason())
                .setNotes(result.getNotes() == null ? List.of() : result.getNotes());
        AiReportRevisionDiff diff = result.getDiff();
        if (diff != null) {
            respVO.setDiff(new AiReportRevisionRespVO.Diff()
                    .setQueryRequired(diff.queryRequired())
                    .setOperations(diff.operations())
                    .setTitleChanged(diff.titleChanged())
                    .setThemeChanged(diff.themeChanged())
                    .setLayoutChanged(diff.layoutChanged())
                    .setAddedBlocks(diff.addedBlocks())
                    .setRemovedBlocks(diff.removedBlocks())
                    .setModifiedBlocks(diff.modifiedBlocks())
                    .setAddedDatasetRefs(diff.addedDatasetRefs())
                    .setRemovedDatasetRefs(diff.removedDatasetRefs()));
        }
        List<AiReportRevisionResultDTO.Candidate> candidates = result.getClarificationCandidates();
        respVO.setClarificationCandidates(
                candidates == null
                        ? List.of()
                        : candidates.stream()
                                .map(candidate -> new AiReportRevisionRespVO.Candidate()
                                        .setCode(candidate.code())
                                        .setLabel(candidate.label()))
                                .toList());
        return respVO;
    }

    private static AiReportSaveDTO toSaveDTO(AiReportSaveReqVO reqVO) {
        return new AiReportSaveDTO()
                .setId(reqVO.getId())
                .setCode(reqVO.getCode())
                .setName(reqVO.getName())
                .setDescription(reqVO.getDescription())
                .setMode(reqVO.getMode())
                .setServiceId(reqVO.getServiceId())
                .setReleaseId(reqVO.getReleaseId())
                .setThemeId(reqVO.getThemeId())
                .setThemeRevision(reqVO.getThemeRevision())
                .setSchemaVersion(reqVO.getSchemaVersion())
                .setSpecJson(reqVO.getSpecJson())
                .setDataJson(reqVO.getDataJson())
                .setSourcesJson(reqVO.getSourcesJson())
                .setCompleteness(reqVO.getCompleteness())
                .setCreatedByRun(reqVO.getCreatedByRun())
                .setVersion(reqVO.getVersion());
    }

    private static AiReportRespVO toRespVO(AiReportDO report) {
        return new AiReportRespVO()
                .setId(report.getId())
                .setCode(report.getCode())
                .setName(report.getName())
                .setDescription(report.getDescription())
                .setMode(report.getMode())
                .setServiceId(report.getServiceId())
                .setReleaseId(report.getReleaseId())
                .setThemeId(report.getThemeId())
                .setThemeRevision(report.getThemeRevision())
                .setSchemaVersion(report.getSchemaVersion())
                .setLatestVersionNo(report.getLatestVersionNo())
                .setPublishedVersionNo(report.getPublishedVersionNo())
                .setVersion(report.getVersion())
                .setCreateTime(report.getCreateTime())
                .setUpdateTime(report.getUpdateTime());
    }

    private static AiReportVersionBriefVO toBriefVO(AiReportVersionDO version) {
        return new AiReportVersionBriefVO()
                .setId(version.getId())
                .setVersionNo(version.getVersionNo())
                .setMode(version.getMode())
                .setCompleteness(version.getCompleteness())
                .setAsOf(version.getAsOf())
                .setCreatedByRun(version.getCreatedByRun())
                .setCreateTime(version.getCreateTime());
    }

    private static AiReportVersionRespVO toVersionRespVO(AiReportVersionDO version) {
        return new AiReportVersionRespVO()
                .setId(version.getId())
                .setReportId(version.getReportId())
                .setVersionNo(version.getVersionNo())
                .setMode(version.getMode())
                .setSpecJson(version.getSpecJson())
                .setDataJson(version.getDataJson())
                .setSourcesJson(version.getSourcesJson())
                .setAsOf(version.getAsOf())
                .setCompleteness(version.getCompleteness())
                .setCreatedByRun(version.getCreatedByRun())
                .setCreateTime(version.getCreateTime());
    }
}
