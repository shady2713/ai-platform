package com.basicframework.module.system.controller.admin.session;

import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.module.system.controller.admin.session.vo.UserSessionPageReqVO;
import com.basicframework.module.system.controller.admin.session.vo.UserSessionRespVO;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.AdminAuthService;
import com.basicframework.module.system.service.session.UserSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台 - 用户会话")
@RestController
@RequestMapping("/system/session")
@Validated
public class UserSessionController {

    private final UserSessionService userSessionService;
    private final AdminAuthService authService;

    public UserSessionController(UserSessionService userSessionService, AdminAuthService authService) {
        this.userSessionService = userSessionService;
        this.authService = authService;
    }

    @GetMapping("/page")
    @Operation(summary = "获得有效用户会话分页")
    @PreAuthorize("@ss.hasPermission('system:session:page')")
    public CommonResult<PageResult<UserSessionRespVO>> getSessionPage(@Valid UserSessionPageReqVO reqVO) {
        PageResult<UserSessionDO> pageResult =
                userSessionService.getSessionPage(reqVO, reqVO.getUserId(), reqVO.getUserType());
        return success(BeanUtils.toBean(pageResult, UserSessionRespVO.class));
    }

    @DeleteMapping("/revoke")
    @Operation(summary = "撤销用户会话")
    @Parameter(name = "id", description = "会话编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:session:revoke')")
    public CommonResult<Boolean> revokeSession(@RequestParam("id") @Positive Long id) {
        authService.logoutByAccessTokenId(getLoginUserId(), id, LoginLogTypeEnum.LOGOUT_DELETE.getType());
        return success(true);
    }

    @DeleteMapping("/revoke-list")
    @Operation(summary = "批量撤销用户会话")
    @Parameter(name = "ids", description = "会话编号数组", required = true)
    @PreAuthorize("@ss.hasPermission('system:session:revoke')")
    public CommonResult<Boolean> revokeSessionList(
            @RequestParam("ids") @Size(min = 1, max = 100) List<@Positive Long> ids) {
        ids.forEach(id ->
                authService.logoutByAccessTokenId(getLoginUserId(), id, LoginLogTypeEnum.LOGOUT_DELETE.getType()));
        return success(true);
    }
}
