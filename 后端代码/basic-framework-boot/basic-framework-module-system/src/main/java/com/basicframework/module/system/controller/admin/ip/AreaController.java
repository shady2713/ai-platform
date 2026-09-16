package com.basicframework.module.system.controller.admin.ip;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AREA_CHINA_NOT_EXISTS;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.ip.core.Area;
import com.basicframework.framework.ip.core.utils.AreaUtils;
import com.basicframework.framework.ip.core.utils.IPUtils;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.impl.UserRateLimiterKeyResolver;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.system.controller.admin.ip.vo.AreaNodeRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 地区 Controller，提供地区树形结构查询能力 */
@Tag(name = "管理后台 - 地区")
@RestController
@RequestMapping("/system/area")
@Validated
@AuthenticatedOnly
public class AreaController {

    @GetMapping("/tree")
    @Operation(summary = "获得地区树")
    @RateLimiter(time = 60, count = 60, message = "查询过于频繁，请稍后重试", keyResolver = UserRateLimiterKeyResolver.class)
    public CommonResult<List<AreaNodeRespVO>> getAreaTree() {
        Area area = AreaUtils.getArea(Area.ID_CHINA);
        if (area == null) {
            throw exception(AREA_CHINA_NOT_EXISTS);
        }
        return success(BeanUtils.toBean(area.getChildren(), AreaNodeRespVO.class));
    }

    @GetMapping("/get-by-ip")
    @Operation(summary = "获得 IP 对应的地区名")
    @Parameter(name = "ip", description = "IP", required = true)
    @RateLimiter(time = 60, count = 60, message = "查询过于频繁，请稍后重试", keyResolver = UserRateLimiterKeyResolver.class)
    public CommonResult<String> getAreaByIp(@RequestParam("ip") String ip) {
        Area area = IPUtils.getArea(ip);
        if (area == null) {
            return success("未知");
        }
        return success(AreaUtils.format(area.getId()));
    }
}
