package com.basicframework.module.ai.controller.admin.connector.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 执行结果（协议层 VO）：分页结论与提取后的条目；不含请求头、凭据与完整响应正文。 */
@Schema(description = "管理后台 - 连接器操作执行结果")
@Data
@Accessors(chain = true)
public class AiConnectorExecuteRespVO {

    @Schema(description = "结论（COMPLETE/PARTIAL/FAILED）")
    private String status;

    @Schema(description = "已执行页数")
    private Integer pages;

    @Schema(description = "提取到的条目数")
    private Integer itemCount;

    @Schema(description = "停止原因（no-more-pages/repeated-cursor/page-limit/upstream-failed）")
    private String stoppedReason;

    @Schema(description = "失败原因码（稳定词表；成功为空）")
    private String detailCode;

    @Schema(description = "提取后的条目")
    private List<String> items;
}
