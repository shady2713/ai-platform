package com.basicframework.module.ai.service.report.refresh.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 报表刷新状态（服务层 DTO）：界面展示"上次刷新时间/结果/原因 + 上一次结果数据"。
 *
 * <p>数据同样受当前 ACL 约束：返回前逐项复核范围指纹，无法证明覆盖即拒绝（AT-048）——
 * "为了页面还能看"不能成为跳过再鉴权的理由。
 */
@Data
@Accessors(chain = true)
public class AiReportRefreshStateDTO {

    /** 报表编号 */
    private Long reportId;

    /** 是否已有刷新尝试（false 表示从未刷新） */
    private boolean attempted;

    /** 最近一次尝试的结果（OK/UNCHANGED/FAILED） */
    private String status;

    /** 最近一次尝试时间 */
    private LocalDateTime asOf;

    /** 最近一次尝试的稳定原因码 */
    private String reason;

    /** 最近一次成功刷新的数据完整性 */
    private String completeness;

    /** 最近一次成功刷新产生的版本号 */
    private Integer resultVersionNo;

    /** 最近一次成功刷新的绑定数据（已按当前 ACL 复核；从未成功刷新时为空） */
    private String dataJson;
}
