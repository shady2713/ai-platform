package com.basicframework.module.system.controller.admin.notify.vo.message;

import static com.basicframework.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.validation.InEnum;
import com.basicframework.module.system.enums.notify.NotifyTemplateTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

@Schema(description = "管理后台 - 站内信分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class NotifyMessagePageReqVO extends PageParam {

    @Schema(description = "用户编号", example = "25025")
    @Positive(message = "用户编号必须大于 0")
    private Long userId;

    @Schema(description = "用户类型", example = "1")
    @Positive(message = "用户类型只允许会员或管理员")
    @InEnum(value = UserTypeEnum.class, message = "用户类型必须是 {value}")
    private Integer userType;

    @Schema(description = "模板编码", example = "test_01")
    @Size(max = 64, message = "模板编码长度不能超过 64 个字符")
    private String templateCode;

    @Schema(description = "模版类型", example = "2")
    @InEnum(value = NotifyTemplateTypeEnum.class, message = "模版类型必须是 {value}")
    private Integer templateType;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;
}
