package com.basicframework.module.system.controller.admin.sms;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.system.controller.admin.sms.vo.channel.SmsChannelPageReqVO;
import com.basicframework.module.system.controller.admin.sms.vo.channel.SmsChannelRespVO;
import com.basicframework.module.system.controller.admin.sms.vo.channel.SmsChannelSaveReqVO;
import com.basicframework.module.system.controller.admin.sms.vo.channel.SmsChannelSimpleRespVO;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import com.basicframework.module.system.service.sms.SmsChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "管理后台 - 短信渠道")
@RestController
@RequestMapping("/system/sms-channel")
@Validated
@RequiredArgsConstructor
public class SmsChannelController {

    private final SmsChannelService smsChannelService;

    @PostMapping("/create")
    @Operation(summary = "创建短信渠道")
    @PreAuthorize("@ss.hasPermission('system:sms-channel:create')")
    public CommonResult<Long> createSmsChannel(@Valid @RequestBody SmsChannelSaveReqVO createReqVO) {
        SmsChannelDO channel = BeanUtils.toBean(createReqVO, SmsChannelDO.class).setId(null);
        return success(smsChannelService.createSmsChannel(channel));
    }

    @PutMapping("/update")
    @Operation(summary = "更新短信渠道")
    @PreAuthorize("@ss.hasPermission('system:sms-channel:update')")
    public CommonResult<Boolean> updateSmsChannel(@Valid @RequestBody SmsChannelSaveReqVO updateReqVO) {
        smsChannelService.updateSmsChannel(BeanUtils.toBean(updateReqVO, SmsChannelDO.class));
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除短信渠道")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('system:sms-channel:delete')")
    public CommonResult<Boolean> deleteSmsChannel(@RequestParam("id") @Positive Long id) {
        smsChannelService.deleteSmsChannel(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @Operation(summary = "批量删除短信渠道")
    @PreAuthorize("@ss.hasPermission('system:sms-channel:delete')")
    public CommonResult<Boolean> deleteSmsChannelList(
            @RequestParam("ids") @Size(min = 1, max = 100) List<@Positive Long> ids) {
        smsChannelService.deleteSmsChannelList(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得短信渠道")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:sms-channel:query')")
    public CommonResult<SmsChannelRespVO> getSmsChannel(@RequestParam("id") @Positive Long id) {
        SmsChannelDO channel = smsChannelService.getSmsChannel(id);
        return success(toResponse(channel));
    }

    @GetMapping("/page")
    @Operation(summary = "获得短信渠道分页")
    @PreAuthorize("@ss.hasPermission('system:sms-channel:query')")
    public CommonResult<PageResult<SmsChannelRespVO>> getSmsChannelPage(@Valid SmsChannelPageReqVO pageVO) {
        PageResult<SmsChannelDO> pageResult = smsChannelService.getSmsChannelPage(
                pageVO, pageVO.getSignature(), pageVO.getCode(), pageVO.getStatus(), pageVO.getCreateTime());
        return success(new PageResult<>(
                pageResult.getList().stream().map(this::toResponse).toList(), pageResult.getTotal()));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获得短信渠道精简列表", description = "包含被禁用的短信渠道")
    @AuthenticatedOnly
    public CommonResult<List<SmsChannelSimpleRespVO>> getSimpleSmsChannelList() {
        List<SmsChannelDO> list = smsChannelService.getSmsChannelList();
        list.sort(Comparator.comparing(SmsChannelDO::getId));
        return success(BeanUtils.toBean(list, SmsChannelSimpleRespVO.class));
    }

    private SmsChannelRespVO toResponse(SmsChannelDO channel) {
        if (channel == null) {
            return null;
        }
        return BeanUtils.toBean(channel, SmsChannelRespVO.class)
                .setApiKeyConfigured(StringUtils.hasText(channel.getApiKeyCiphertext()))
                .setApiSecretConfigured(StringUtils.hasText(channel.getApiSecretCiphertext()));
    }
}
