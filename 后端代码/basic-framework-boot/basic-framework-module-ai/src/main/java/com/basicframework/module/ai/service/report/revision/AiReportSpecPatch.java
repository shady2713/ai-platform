package com.basicframework.module.ai.service.report.revision;

import java.util.Map;

/**
 * 修订补丁结果（R05）：候选规格 + 与基础版本的差异 + 受控查询结果落在哪个数据集引用上。
 *
 * <p>候选规格是**已通过 R01 结构校验的 JSON**（补丁器出口即解析一次，形状错误当场暴露），
 * 语义校验（引用/类型/布局）由调用方用同一个校验器再跑一次——补丁器不替代校验器。
 *
 * <p>{@code datasetRefByDatasetId} 让调用方精确知道"哪次受控查询写进了哪个数据集引用"：
 * 绑定与差异都不能靠"按数据集标识猜引用"，同一数据集在修订后可能同时存在新旧两个引用。
 */
public record AiReportSpecPatch(String specJson, AiReportRevisionDiff diff, Map<Long, String> datasetRefByDatasetId) {

    public AiReportSpecPatch {
        datasetRefByDatasetId = datasetRefByDatasetId == null ? Map.of() : Map.copyOf(datasetRefByDatasetId);
    }
}
