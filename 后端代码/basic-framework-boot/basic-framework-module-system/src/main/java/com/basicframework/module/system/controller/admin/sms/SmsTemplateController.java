package com.basicframework.module.system.controller.admin.sms;

import static com.basicframework.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.apilog.core.annotation.ApiAccessLog;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.module.system.controller.admin.sms.vo.template.SmsTemplatePageReqVO;
import com.basicframework.module.system.controller.admin.sms.vo.template.SmsTemplateRespVO;
import com.basicframework.module.system.controller.admin.sms.vo.template.SmsTemplateSaveReqVO;
import com.basicframework.module.system.controller.admin.sms.vo.template.SmsTemplateSendReqVO;
import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import com.basicframework.module.system.dal.mysql.sms.SmsTemplateQuery;
import com.basicframework.module.system.service.sms.SmsSendService;
import com.basicframework.module.system.service.sms.SmsTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "管理后台 - 短信模板")
@RestController
@RequestMapping("/system/sms-template")
@Validated
public class SmsTemplateController {

    private final SmsTemplateService smsTemplateService;
    private final SmsSendService smsSendService;

    public SmsTemplateController(SmsTemplateService smsTemplateService, SmsSendService smsSendService) {
        this.smsTemplateService = smsTemplateService;
        this.smsSendService = smsSendService;
    }

    @PostMapping("/create")
    @Operation(summary = "创建短信模板")
    @PreAuthorize("@ss.hasPermission('system:sms-template:create')")
    public CommonResult<Long> createSmsTemplate(@Valid @RequestBody SmsTemplateSaveReqVO createReqVO) {
        return success(smsTemplateService.createSmsTemplate(BeanUtils.toBean(createReqVO, SmsTemplateDO.class)));
    }

    @PutMapping("/update")
    @Operation(summary = "更新短信模板")
    @PreAuthorize("@ss.hasPermission('system:sms-template:update')")
    public CommonResult<Boolean> updateSmsTemplate(@Valid @RequestBody SmsTemplateSaveReqVO updateReqVO) {
        smsTemplateService.updateSmsTemplate(BeanUtils.toBean(updateReqVO, SmsTemplateDO.class));
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除短信模板")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('system:sms-template:delete')")
    public CommonResult<Boolean> deleteSmsTemplate(@RequestParam("id") @Positive Long id) {
        smsTemplateService.deleteSmsTemplate(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @Operation(summary = "批量删除短信模板")
    @PreAuthorize("@ss.hasPermission('system:sms-template:delete')")
    public CommonResult<Boolean> deleteSmsTemplateList(@RequestParam("ids") List<Long> ids) {
        smsTemplateService.deleteSmsTemplateList(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得短信模板")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:sms-template:query')")
    public CommonResult<SmsTemplateRespVO> getSmsTemplate(@RequestParam("id") @Positive Long id) {
        SmsTemplateDO template = smsTemplateService.getSmsTemplate(id);
        return success(BeanUtils.toBean(template, SmsTemplateRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得短信模板分页")
    @PreAuthorize("@ss.hasPermission('system:sms-template:query')")
    public CommonResult<PageResult<SmsTemplateRespVO>> getSmsTemplatePage(@Valid SmsTemplatePageReqVO pageVO) {
        PageResult<SmsTemplateDO> pageResult = smsTemplateService.getSmsTemplatePage(
                pageVO,
                new SmsTemplateQuery(
                        pageVO.getType(),
                        pageVO.getStatus(),
                        pageVO.getCode(),
                        pageVO.getContent(),
                        pageVO.getApiTemplateId(),
                        pageVO.getChannelId(),
                        pageVO.getCreateTime()));
        return success(BeanUtils.toBean(pageResult, SmsTemplateRespVO.class));
    }

    @GetMapping("/export-excel")
    @Operation(summary = "导出短信模板 Excel")
    @PreAuthorize("@ss.hasPermission('system:sms-template:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void export(HttpServletResponse response, @Valid SmsTemplatePageReqVO exportReqVO) throws IOException {
        exportReqVO.setPageSize(PageParam.EXPORT_MAX_PAGE_SIZE);
        List<SmsTemplateDO> list = smsTemplateService
                .getSmsTemplatePage(
                        exportReqVO,
                        new SmsTemplateQuery(
                                exportReqVO.getType(),
                                exportReqVO.getStatus(),
                                exportReqVO.getCode(),
                                exportReqVO.getContent(),
                                exportReqVO.getApiTemplateId(),
                                exportReqVO.getChannelId(),
                                exportReqVO.getCreateTime()))
                .getList();
        // 导出 Excel
        ExcelUtils.write(
                response, "短信模板.xls", "数据", SmsTemplateRespVO.class, BeanUtils.toBean(list, SmsTemplateRespVO.class));
    }

    @PostMapping("/send-sms")
    @Operation(summary = "发送短信")
    @PreAuthorize("@ss.hasPermission('system:sms-template:send-sms')")
    public CommonResult<Long> sendSms(@Valid @RequestBody SmsTemplateSendReqVO sendReqVO) {
        return success(smsSendService.sendSingleSmsToAdmin(
                sendReqVO.getMobile(), null, sendReqVO.getTemplateCode(), sendReqVO.getTemplateParams()));
    }
}
