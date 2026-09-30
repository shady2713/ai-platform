package com.basicframework.module.ai.service.semantic.dto;

import lombok.Data;

/** 跨源指标口径登记入参（Y03）：新建统一口径的锚点。 */
@Data
public class AiMetricSemanticsSaveDTO {

    /** 口径标识（全局唯一，登记后不可修改——它是跨源聚合的锚点） */
    private String metricCode;

    /** 口径名称（仅展示） */
    private String metricName;

    /** 说明（不承载判定语义） */
    private String description;
}
