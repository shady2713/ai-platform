package com.basicframework.module.system.controller.admin.session.vo;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.validation.InEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 用户会话分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class UserSessionPageReqVO extends PageParam {

    @Schema(description = "用户编号", example = "666")
    @Positive
    private Long userId;

    @Schema(description = "用户类型，参见 UserTypeEnum 枚举", example = "2")
    @InEnum(UserTypeEnum.class)
    private Integer userType;
}
