package com.basicframework.module.ai.service.query.planner.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 查询规划请求（服务层 DTO）。 */
@Data
@Accessors(chain = true)
public class AiQueryPlanRequestDTO {

    /** 数据集编号（必填） */
    private Long datasetId;

    /** 数据集版本编号（可选；缺省取最新已发布版本） */
    private Long datasetVersionId;

    /** 模型端点编号（必填） */
    private Long endpointId;

    /** 用户问题（必填） */
    private String question;

    /**
     * 本次调用允许的数据集集合（可选）：调用方（服务运行链路/调试入口）给出的授权范围。
     * 为空表示只允许 {@link #datasetId} 本身；修复重试**永不扩大**该集合。
     */
    private List<Long> allowedDatasetIds;

    /** 本次允许的字段/指标码（可选）：为空表示数据集定义里的全部字段。 */
    private List<String> allowedFieldCodes;

    /** 允许的修复次数（可选，上限 {@link #MAX_REPAIRS}）。 */
    private Integer maxRepairs;

    /** 修复次数硬上限：模型连续给出不合规计划时到此为止。 */
    public static final int MAX_REPAIRS = 2;

    public int effectiveMaxRepairs() {
        if (maxRepairs == null) {
            return MAX_REPAIRS;
        }
        return Math.max(0, Math.min(MAX_REPAIRS, maxRepairs));
    }
}
