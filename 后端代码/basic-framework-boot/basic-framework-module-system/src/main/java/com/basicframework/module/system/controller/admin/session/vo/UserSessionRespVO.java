package com.basicframework.module.system.controller.admin.session.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;

@Schema(description = "管理后台 - 用户会话 Response VO")
@Data
public class UserSessionRespVO {

    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "用户编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "666")
    private Long userId;

    @Schema(description = "用户类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer userType;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

    @Schema(description = "访问令牌过期时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime accessExpiresTime;

    @Schema(description = "刷新令牌绝对过期时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime refreshExpiresTime;
}
