package com.basicframework.module.system.controller.admin.notify;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.module.system.enums.ErrorCodeConstants.NOTIFY_SEND_USER_TYPE_INVALID;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.module.system.controller.admin.notify.vo.template.NotifyTemplatePageReqVO;
import com.basicframework.module.system.controller.admin.notify.vo.template.NotifyTemplateRespVO;
import com.basicframework.module.system.controller.admin.notify.vo.template.NotifyTemplateSaveReqVO;
import com.basicframework.module.system.controller.admin.notify.vo.template.NotifyTemplateSendReqVO;
import com.basicframework.module.system.dal.dataobject.notify.NotifyTemplateDO;
import com.basicframework.module.system.dal.mysql.notify.NotifyTemplateQuery;
import com.basicframework.module.system.service.notify.NotifySendService;
import com.basicframework.module.system.service.notify.NotifyTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "管理后台 - 站内信模版")
@RestController
@RequestMapping("/system/notify-template")
@Validated
public class NotifyTemplateController {

    private final NotifyTemplateService notifyTemplateService;
    private final NotifySendService notifySendService;

    public NotifyTemplateController(NotifyTemplateService notifyTemplateService, NotifySendService notifySendService) {
        this.notifyTemplateService = notifyTemplateService;
        this.notifySendService = notifySendService;
    }

    @PostMapping("/create")
    @Operation(summary = "创建站内信模版")
    @PreAuthorize("@ss.hasPermission('system:notify-template:create')")
    public CommonResult<Long> createNotifyTemplate(@Valid @RequestBody NotifyTemplateSaveReqVO createReqVO) {
        return success(
                notifyTemplateService.createNotifyTemplate(BeanUtils.toBean(createReqVO, NotifyTemplateDO.class)));
    }

    @PutMapping("/update")
    @Operation(summary = "更新站内信模版")
    @PreAuthorize("@ss.hasPermission('system:notify-template:update')")
    public CommonResult<Boolean> updateNotifyTemplate(@Valid @RequestBody NotifyTemplateSaveReqVO updateReqVO) {
        notifyTemplateService.updateNotifyTemplate(BeanUtils.toBean(updateReqVO, NotifyTemplateDO.class));
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除站内信模版")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('system:notify-template:delete')")
    public CommonResult<Boolean> deleteNotifyTemplate(@RequestParam("id") @Positive Long id) {
        notifyTemplateService.deleteNotifyTemplate(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除站内信模版")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('system:notify-template:delete')")
    public CommonResult<Boolean> deleteNotifyTemplateList(
            @RequestParam("ids") @Size(min = 1, max = 100) List<@Positive Long> ids) {
        notifyTemplateService.deleteNotifyTemplateList(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得站内信模版")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:notify-template:query')")
    public CommonResult<NotifyTemplateRespVO> getNotifyTemplate(@RequestParam("id") @Positive Long id) {
        NotifyTemplateDO template = notifyTemplateService.getNotifyTemplate(id);
        return success(BeanUtils.toBean(template, NotifyTemplateRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得站内信模版分页")
    @PreAuthorize("@ss.hasPermission('system:notify-template:query')")
    public CommonResult<PageResult<NotifyTemplateRespVO>> getNotifyTemplatePage(@Valid NotifyTemplatePageReqVO pageVO) {
        PageResult<NotifyTemplateDO> pageResult = notifyTemplateService.getNotifyTemplatePage(
                pageVO,
                new NotifyTemplateQuery(
                        pageVO.getCode(),
                        pageVO.getName(),
                        pageVO.getType(),
                        pageVO.getStatus(),
                        pageVO.getCreateTime()));
        return success(BeanUtils.toBean(pageResult, NotifyTemplateRespVO.class));
    }

    @PostMapping("/send-notify")
    @Operation(summary = "发送站内信")
    @PreAuthorize("@ss.hasPermission('system:notify-template:send-notify')")
    public CommonResult<Long> sendNotify(@Valid @RequestBody NotifyTemplateSendReqVO sendReqVO) {
        if (UserTypeEnum.MEMBER.getValue().equals(sendReqVO.getUserType())) {
            return success(notifySendService.sendSingleNotifyToMember(
                    sendReqVO.getUserId(), sendReqVO.getTemplateCode(), sendReqVO.getTemplateParams()));
        }
        if (UserTypeEnum.ADMIN.getValue().equals(sendReqVO.getUserType())) {
            return success(notifySendService.sendSingleNotifyToAdmin(
                    sendReqVO.getUserId(), sendReqVO.getTemplateCode(), sendReqVO.getTemplateParams()));
        }
        throw exception(NOTIFY_SEND_USER_TYPE_INVALID);
    }
}
