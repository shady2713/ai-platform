package com.basicframework.module.ai.domain.query;

import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import java.util.List;

/**
 * 已解析的数据集版本（D05）：规划器与校验器看到的**唯一**数据集事实。
 *
 * <p>字段与指标已经按调用方给出的授权范围裁剪：校验器不会、也无法"发现"授权外的字段，
 * 因此"模型不能扩大查询范围"是结构性保证，而不是靠提示词约束。
 */
public record ResolvedDatasetVersion(
        Long datasetId,
        String datasetCode,
        Long datasetVersionId,
        Integer datasetVersionNo,
        String schemaHash,
        String sourceObject,
        AiDatasetDefinition definition,
        List<String> allowedFieldCodes) {

    public ResolvedDatasetVersion {
        allowedFieldCodes = allowedFieldCodes == null ? List.of() : List.copyOf(allowedFieldCodes);
    }

    /** 计划里的数据集标识（与 query-plan.schema.json 的 {@code dset_} 前缀一致）。 */
    public String planDatasetId() {
        return "dset_" + datasetCode;
    }

    /** 字段是否在本次授权范围内（未声明授权集合时表示"全部已声明字段"）。 */
    public boolean allowsField(String code) {
        return allowedFieldCodes.isEmpty() || allowedFieldCodes.contains(code);
    }
}
