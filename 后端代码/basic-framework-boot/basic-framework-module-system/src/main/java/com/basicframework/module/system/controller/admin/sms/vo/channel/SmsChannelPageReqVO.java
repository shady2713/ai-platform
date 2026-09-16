package com.basicframework.module.system.controller.admin.sms.vo.channel;

import static com.basicframework.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.validation.InEnum;
import com.basicframework.module.system.framework.sms.core.enums.SmsChannelEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

@Schema(description = "管理后台 - 短信渠道分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SmsChannelPageReqVO extends PageParam {

    @Schema(description = "任务状态", example = "1")
    @InEnum(value = CommonStatusEnum.class, message = "状态必须是 {value}")
    private Integer status;

    @Schema(description = "短信签名，模糊匹配", example = "基础框架")
    @Size(max = 12, message = "短信签名长度不能超过 12 个字符")
    private String signature;

    @Schema(description = "短信渠道编码", example = "ALIYUN")
    @Size(max = 63, message = "短信渠道编码长度不能超过 63 个字符")
    @InEnum(value = SmsChannelEnum.class, message = "短信渠道编码必须是 {value}")
    private String code;

    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Schema(description = "创建时间")
    @Size(min = 2, max = 2, message = "创建时间必须包含起止两个端点")
    private LocalDateTime[] createTime;
}
