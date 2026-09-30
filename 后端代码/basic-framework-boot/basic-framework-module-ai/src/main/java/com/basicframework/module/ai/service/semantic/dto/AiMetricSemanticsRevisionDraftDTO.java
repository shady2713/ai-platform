package com.basicframework.module.ai.service.semantic.dto;

import lombok.Data;

/**
 * 跨源指标口径版本草稿（Y03）：创建草稿版本的入参。
 *
 * <p>与 Y02 一样，草稿可编辑、发布后不可变；因此这里只承载"要写进版本的内容"，
 * 不承载发布动作（发布是独立审核，见 {@code AiMetricSemanticsServiceImpl}）。
 */
@Data
public class AiMetricSemanticsRevisionDraftDTO {

    /** 跨源指标口径标识（全局唯一、稳定且不可修改） */
    private String metricCode;

    /** 口径名称（仅展示） */
    private String metricName;

    /** 口径定义 JSON（币种/单位/时区/时间窗口/来源/聚合顺序/换算规则） */
    private String definitionJson;

    /** 版本有效期起点（含） */
    private String validFrom;

    /** 版本有效期终点（不含；空 = 长期有效） */
    private String validTo;
}
