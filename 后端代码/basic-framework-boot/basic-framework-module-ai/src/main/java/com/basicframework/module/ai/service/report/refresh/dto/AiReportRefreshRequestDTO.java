package com.basicframework.module.ai.service.report.refresh.dto;

import com.basicframework.module.ai.domain.query.QueryScope;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 报表刷新请求（服务层 DTO）。
 *
 * <p>归属来自服务端：有会话时按会话身份校验归属，没有会话（作业）时以**报表自身的归属列**为主体，
 * 两者都不接受调用方指定身份。行范围（{@link #rowScope}）来自授权层，请求体不提供——
 * 没有行范围时刷新按稳定原因失败并留痕，而不是退回全库。
 */
@Data
@Accessors(chain = true)
public class AiReportRefreshRequestDTO {

    /** 报表编号（必填） */
    private Long reportId;

    /** 行范围（数据类刷新必填且必须有效；来自授权层） */
    private QueryScope rowScope;

    /** 来源运行标识（可选，写入新版本用于可追溯） */
    private String createdByRun;
}
