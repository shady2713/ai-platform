package com.basicframework.module.system.controller.admin.notify.vo.template;

import static com.basicframework.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.validation.InEnum;
import com.basicframework.module.system.enums.notify.NotifyTemplateTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

@Schema(description = "管理后台 - 站内信模版分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class NotifyTemplatePageReqVO extends PageParam {

    @Schema(description = "模版编码", example = "test_01")
    @Size(max = 64, message = "模版编码长度不能超过 64 个字符")
    private String code;

    @Schema(description = "模版名称", example = "我是名称")
    @Size(max = 63, message = "模版名称长度不能超过 63 个字符")
    private String name;

    @Schema(description = "模版类型，对应 system_notify_template_type 字典", example = "1")
    @InEnum(value = NotifyTemplateTypeEnum.class, message = "模版类型必须是 {value}")
    private Integer type;

    @Schema(description = "状态，参见 CommonStatusEnum 枚举类", example = "1")
    @InEnum(value = CommonStatusEnum.class, message = "状态必须是 {value}")
    private Integer status;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Size(min = 2, max = 2, message = "创建时间必须包含起止两个端点")
    private LocalDateTime[] createTime;
}
