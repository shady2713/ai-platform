package com.basicframework.module.ai.controller.admin.theme;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.theme.vo.AiThemeEffectiveRespVO;
import com.basicframework.module.ai.controller.admin.theme.vo.AiThemePageReqVO;
import com.basicframework.module.ai.controller.admin.theme.vo.AiThemePublishReqVO;
import com.basicframework.module.ai.controller.admin.theme.vo.AiThemeRespVO;
import com.basicframework.module.ai.controller.admin.theme.vo.AiThemeSaveReqVO;
import com.basicframework.module.ai.dal.dataobject.theme.AiThemeDO;
import com.basicframework.module.ai.domain.theme.AiThemeValidator;
import com.basicframework.module.ai.service.theme.AiThemeService;
import com.basicframework.module.ai.service.theme.dto.AiThemeEffectiveDTO;
import com.basicframework.module.ai.service.theme.dto.AiThemeSaveDTO;
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
 * AI 主题管理接口（C04）。
 *
 * <p>权限码与 V79 迁移的菜单种子一一对应：查询 {@code ai:theme:query}、
 * 创建修订 {@code ai:theme:create}、发布/回退 {@code ai:theme:publish}。
 *
 * <p>响应只含已校验的 token/布局与版本信息，不含任何凭据；字体白名单通过
 * {@code /fonts} 暴露给管理端做下拉选项，避免前端各自维护一份允许列表。
 */
@Tag(name = "管理后台 - AI 主题")
@RestController
@RequestMapping("/ai/theme")
@Validated
@RequiredArgsConstructor
public class AiThemeController {

    private final AiThemeService themeService;

    @PostMapping("/create")
    @Operation(summary = "新建主题修订草稿（发布后不可修改，调整即新建修订）")
    @PreAuthorize("@ss.hasPermission('ai:theme:create')")
    public CommonResult<Long> create(@Valid @RequestBody AiThemeSaveReqVO reqVO) {
        AiThemeSaveDTO saveDTO = new AiThemeSaveDTO()
                .setApplicationId(reqVO.getApplicationId())
                .setTokensJson(reqVO.getTokensJson())
                .setLayoutJson(reqVO.getLayoutJson());
        return success(themeService.create(saveDTO));
    }

    @GetMapping("/get")
    @Operation(summary = "查询主题修订")
    @PreAuthorize("@ss.hasPermission('ai:theme:query')")
    public CommonResult<AiThemeRespVO> get(
            @Parameter(description = "主题修订编号", required = true) @RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(themeService.getTheme(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询主题修订")
    @PreAuthorize("@ss.hasPermission('ai:theme:query')")
    public CommonResult<PageResult<AiThemeRespVO>> page(@Valid AiThemePageReqVO pageReqVO) {
        PageResult<AiThemeDO> page =
                themeService.getThemePage(pageReqVO, pageReqVO.getApplicationId(), pageReqVO.getPublicationState());
        return success(new PageResult<>(
                page.getList().stream().map(AiThemeController::toRespVO).toList(), page.getTotal()));
    }

    @PostMapping("/publish")
    @Operation(summary = "发布或回退主题修订（同一动作；目标为历史修订即回退）")
    @PreAuthorize("@ss.hasPermission('ai:theme:publish')")
    public CommonResult<Boolean> publish(@Valid @RequestBody AiThemePublishReqVO reqVO) {
        themeService.publish(reqVO.getId(), reqVO.getVersion());
        return success(true);
    }

    @GetMapping("/effective")
    @Operation(summary = "解析有效主题（平台默认 → 应用已发布修订；运行时覆盖不落库）")
    @PreAuthorize("@ss.hasPermission('ai:theme:query')")
    public CommonResult<AiThemeEffectiveRespVO> effective(
            @Parameter(description = "应用编号", required = true) @RequestParam("applicationId") @NotNull @Positive
                    Long applicationId) {
        return success(toEffectiveRespVO(themeService.resolveEffective(applicationId)));
    }

    @GetMapping("/fonts")
    @Operation(summary = "允许的字体栈（自托管白名单；不接受远程字体地址）")
    @PreAuthorize("@ss.hasPermission('ai:theme:query')")
    public CommonResult<List<String>> fonts() {
        return success(AiThemeValidator.allowedFontFamilies());
    }

    private static AiThemeRespVO toRespVO(AiThemeDO theme) {
        return new AiThemeRespVO()
                .setId(theme.getId())
                .setPublicId(theme.getPublicId())
                .setApplicationId(theme.getApplicationId())
                .setRevision(theme.getRevision())
                .setTokensJson(theme.getTokensJson())
                .setLayoutJson(theme.getLayoutJson())
                .setTokensFingerprint(theme.getTokensFingerprint())
                .setPublicationState(theme.getPublicationState())
                .setPublishedTime(theme.getPublishedTime())
                .setVersion(theme.getVersion())
                .setCreateTime(theme.getCreateTime());
    }

    private static AiThemeEffectiveRespVO toEffectiveRespVO(AiThemeEffectiveDTO effective) {
        return new AiThemeEffectiveRespVO()
                .setApplicationId(effective.getApplicationId())
                .setPublicId(effective.getPublicId())
                .setRevision(effective.getRevision())
                .setFingerprint(effective.getFingerprint())
                .setSource(effective.getSource())
                .setTokensJson(effective.getTokensJson())
                .setLayoutJson(effective.getLayoutJson());
    }
}
