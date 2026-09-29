package com.basicframework.module.ai.controller.app.v1.report;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportShareAccessRespVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportShareCreateReqVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportShareCreateRespVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportSharePageReqVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportShareReadRespVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportShareRespVO;
import com.basicframework.module.ai.controller.app.v1.report.vo.AiReportShareRevokeReqVO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareAccessDO;
import com.basicframework.module.ai.dal.dataobject.report.AiReportShareDO;
import com.basicframework.module.ai.service.report.share.AiReportShareService;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateDTO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareCreateResultDTO;
import com.basicframework.module.ai.service.report.share.dto.AiReportShareReadDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * AI 应用端报表受控分享（X11）。
 *
 * <p>所有端点都只要求登录（{@code @AuthenticatedOnly}）：授予者与接收者身份都来自**服务端会话**，
 * 请求体只描述"分享给谁、哪个版本、到什么时候"。可见权与源数据读取权分离——凭据只让接收者
 * 打开分享，内容出库前按接收者当前源权限逐项复核（覆盖不了就是降级态：隐藏统计与快照）。
 * 撤销、到期与授予者停用立即生效；五类前置失败对外统一 404（防枚举），原因只进访问审计。
 */
@Tag(name = "AI 应用端 - 报表受控分享")
@RestController
@RequestMapping("/ai/report/share")
@Validated
@RequiredArgsConstructor
public class AiReportShareController {

    private final AiReportShareService shareService;

    @PostMapping("/create")
    @Operation(summary = "创建分享（同报表 + 同接收者的生效分享已存在则 409；明文令牌只在本次响应出现）")
    @AuthenticatedOnly
    public CommonResult<AiReportShareCreateRespVO> create(@Valid @RequestBody AiReportShareCreateReqVO reqVO) {
        AiReportShareCreateResultDTO result = shareService.create(new AiReportShareCreateDTO()
                .setReportId(reqVO.getReportId())
                .setVersionNo(reqVO.getVersionNo())
                .setGranteeExternalUserId(reqVO.getGranteeExternalUserId())
                .setExpiresTime(reqVO.getExpiresTime()));
        return success(new AiReportShareCreateRespVO()
                .setShareId(result.getShareId())
                .setToken(result.getToken())
                .setVersionNo(result.getVersionNo())
                .setGranteeExternalUserId(result.getGranteeExternalUserId())
                .setGranteeDisplayName(result.getGranteeDisplayName())
                .setExpiresTime(result.getExpiresTime()));
    }

    @PostMapping("/revoke")
    @Operation(summary = "撤销分享（仅授予者本人；乐观锁 CAS，重复撤销幂等成功）")
    @AuthenticatedOnly
    public CommonResult<Boolean> revoke(@Valid @RequestBody AiReportShareRevokeReqVO reqVO) {
        shareService.revoke(reqVO.getShareId(), reqVO.getVersion());
        return success(true);
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询当前授予者的分享（显示接收范围）")
    @AuthenticatedOnly
    public CommonResult<PageResult<AiReportShareRespVO>> page(@Valid AiReportSharePageReqVO reqVO) {
        PageResult<AiReportShareDO> page = shareService.getSharePage(reqVO);
        return success(new PageResult<>(
                page.getList().stream().map(AiReportShareController::toRespVO).toList(), page.getTotal()));
    }

    @GetMapping("/read")
    @Operation(
            summary = "接收者按令牌读取分享（源权限覆盖不了时返回降级态：内容字段为空）",
            description = "前置失败（非接收者/已撤销/已过期/授予者停用/凭据未知）一律 404，与分享不存在同语义")
    @AuthenticatedOnly
    public CommonResult<AiReportShareReadRespVO> read(
            @Parameter(description = "分享令牌", required = true) @RequestParam("token") @NotBlank String token) {
        return success(toReadRespVO(shareService.readByToken(token)));
    }

    @GetMapping("/access/list")
    @Operation(summary = "查询分享的访问审计（仅授予者本人可查，最新在前）")
    @AuthenticatedOnly
    public CommonResult<List<AiReportShareAccessRespVO>> accessList(
            @Parameter(description = "分享编号", required = true) @RequestParam("shareId") @NotNull @Positive
                    Long shareId) {
        return success(shareService.getAccessRecords(shareId).stream()
                .map(AiReportShareController::toAccessRespVO)
                .toList());
    }

    private static AiReportShareRespVO toRespVO(AiReportShareDO share) {
        return new AiReportShareRespVO()
                .setId(share.getId())
                .setReportId(share.getReportId())
                .setVersionNo(share.getVersionNo())
                .setStatus(share.getStatus())
                .setGranteeExternalUserId(share.getGranteeExternalUserId())
                .setGranteeDisplayName(share.getGranteeDisplayName())
                .setExpiresTime(share.getExpiresTime())
                .setCreateTime(share.getCreateTime());
    }

    private static AiReportShareReadRespVO toReadRespVO(AiReportShareReadDTO dto) {
        return new AiReportShareReadRespVO()
                .setShareId(dto.getShareId())
                .setReportId(dto.getReportId())
                .setReportName(dto.getReportName())
                .setVersionNo(dto.getVersionNo())
                .setMode(dto.getMode())
                .setContentAuthorized(dto.isContentAuthorized())
                .setReasonCode(dto.getReasonCode())
                .setSpecJson(dto.getSpecJson())
                .setDataJson(dto.getDataJson())
                .setAsOf(dto.getAsOf())
                .setCompleteness(dto.getCompleteness())
                .setExpiresTime(dto.getExpiresTime());
    }

    private static AiReportShareAccessRespVO toAccessRespVO(AiReportShareAccessDO access) {
        return new AiReportShareAccessRespVO()
                .setId(access.getId())
                .setShareId(access.getShareId())
                .setSubjectType(access.getSubjectType())
                .setExternalUserId(access.getExternalUserId())
                .setOutcome(access.getOutcome())
                .setReasonCode(access.getReasonCode())
                .setContentAuthorized(access.getContentAuthorized())
                .setCreateTime(access.getCreateTime());
    }
}
