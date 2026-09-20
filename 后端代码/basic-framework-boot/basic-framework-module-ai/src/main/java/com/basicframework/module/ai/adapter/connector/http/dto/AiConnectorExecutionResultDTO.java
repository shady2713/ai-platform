package com.basicframework.module.ai.adapter.connector.http.dto;

import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 连接器操作执行结果（D02）：分页结论 + 提取后的条目，不含请求头、凭据与完整响应正文。
 *
 * <p>{@code status} 三态：{@code COMPLETE}（已取完）、{@code PARTIAL}（达到页数上限，未取完）、
 * {@code FAILED}（上游或策略失败，{@code detailCode} 给稳定原因码）。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"items"})
public class AiConnectorExecutionResultDTO {

    /** 结论（COMPLETE/PARTIAL/FAILED） */
    private String status;

    /** 已执行页数 */
    private int pages;

    /** 提取到的条目数 */
    private int itemCount;

    /** 停止原因（稳定说明：no-more-pages / repeated-cursor / page-limit / upstream-failed） */
    private String stoppedReason;

    /** 失败原因码（稳定词表；成功为空） */
    private String detailCode;

    /** 提取后的条目（已按响应提取规则裁剪；不进 toString） */
    private List<String> items;
}
