package com.basicframework.module.ai.controller.admin.debug;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.module.ai.controller.admin.debug.vo.AiContextSectionStatRespVO;
import com.basicframework.module.ai.controller.admin.debug.vo.AiDebugStageRespVO;
import com.basicframework.module.ai.controller.admin.debug.vo.AiServiceDebugResultRespVO;
import com.basicframework.module.ai.controller.admin.debug.vo.AiServiceDebugRunReqVO;
import com.basicframework.module.ai.service.context.dto.AiContextHistoryDTO;
import com.basicframework.module.ai.service.context.dto.AiContextSectionStatDTO;
import com.basicframework.module.ai.service.debug.AiServiceDebugService;
import com.basicframework.module.ai.service.debug.dto.AiDebugStageDTO;
import com.basicframework.module.ai.service.debug.dto.AiServiceDebugResultDTO;
import com.basicframework.module.ai.service.debug.dto.AiServiceDebugRunDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 服务调试接口（S04）。
 *
 * <p>权限码 {@code ai:service:debug} 与 V58 迁移的菜单种子一致；调试必须显式给出测试主体，
 * 平台按该主体的当前授权判定发布版本绑定的资源动作，因此调试不会比测试主体看得更多。
 * 响应只包含阶段摘要、分区统计与可见输出：不回显提示词正文，也不返回隐藏推理。
 */
@Tag(name = "管理后台 - AI 服务调试")
@RestController
@RequestMapping("/ai/service/debug")
@Validated
@RequiredArgsConstructor
public class AiServiceDebugController {

    private final AiServiceDebugService debugService;

    @PostMapping("/run")
    @Operation(summary = "调试运行（显式测试主体 + 当前生效版本，返回阶段摘要与证据）")
    @PreAuthorize("@ss.hasPermission('ai:service:debug')")
    public CommonResult<AiServiceDebugResultRespVO> run(@Valid @RequestBody AiServiceDebugRunReqVO reqVO) {
        return success(toRespVO(debugService.debugRun(toRunDTO(reqVO))));
    }

    private static AiServiceDebugRunDTO toRunDTO(AiServiceDebugRunReqVO reqVO) {
        return new AiServiceDebugRunDTO()
                .setServiceId(reqVO.getServiceId())
                .setTestSubjectType(reqVO.getTestSubjectType())
                .setTestSubjectId(reqVO.getTestSubjectId())
                .setUserMessage(reqVO.getUserMessage())
                .setBusinessContext(reqVO.getBusinessContext())
                .setDataLevel(reqVO.getDataLevel())
                .setTimeoutMillis(reqVO.getTimeoutMillis())
                .setMaxMessages(reqVO.getMaxMessages())
                .setMaxTokens(reqVO.getMaxTokens())
                .setHistory(
                        reqVO.getHistory() == null
                                ? null
                                : reqVO.getHistory().stream()
                                        .map(item -> new AiContextHistoryDTO()
                                                .setRole(item.getRole())
                                                .setContent(item.getContent()))
                                        .collect(Collectors.toList()));
    }

    private static AiServiceDebugResultRespVO toRespVO(AiServiceDebugResultDTO result) {
        return new AiServiceDebugResultRespVO()
                .setReleaseId(result.getReleaseId())
                .setReleaseVersion(result.getReleaseVersion())
                .setContentHash(result.getContentHash())
                .setModelEndpointId(result.getModelEndpointId())
                .setModelRevision(result.getModelRevision())
                .setTestSubjectType(result.getTestSubjectType())
                .setTestSubjectId(result.getTestSubjectId())
                .setAuthorizedBindings(result.getAuthorizedBindings())
                .setSections(toSectionVOs(result.getSections()))
                .setEstimatedTokens(result.getEstimatedTokens())
                .setTruncated(result.getTruncated())
                .setStages(toStageVOs(result.getStages()))
                .setOutput(result.getOutput())
                .setStructured(result.getStructured())
                .setInputTokens(result.getInputTokens())
                .setOutputTokens(result.getOutputTokens())
                .setDurationMs(result.getDurationMs());
    }

    private static List<AiContextSectionStatRespVO> toSectionVOs(List<AiContextSectionStatDTO> sections) {
        return sections == null
                ? List.of()
                : sections.stream()
                        .map(section -> new AiContextSectionStatRespVO()
                                .setSection(section.getSection().name())
                                .setIncludedCount(section.getIncludedCount())
                                .setDroppedCount(section.getDroppedCount())
                                .setEstimatedTokens(section.getEstimatedTokens())
                                .setTruncated(section.isTruncated())
                                .setSanitized(section.isSanitized()))
                        .collect(Collectors.toList());
    }

    private static List<AiDebugStageRespVO> toStageVOs(List<AiDebugStageDTO> stages) {
        return stages == null
                ? List.of()
                : stages.stream()
                        .map(stage -> new AiDebugStageRespVO()
                                .setStage(stage.getStage())
                                .setStatus(stage.getStatus())
                                .setDetail(stage.getDetail())
                                .setDurationMs(stage.getDurationMs()))
                        .collect(Collectors.toList());
    }
}
