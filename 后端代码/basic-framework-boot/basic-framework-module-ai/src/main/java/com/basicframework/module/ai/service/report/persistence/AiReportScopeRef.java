package com.basicframework.module.ai.service.report.persistence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 报表版本的单项资源依赖（R04 第 4 步）：A03 词表里的资源类型 + 资源标识 + 保存时该次判定的范围指纹。
 *
 * <p>用可序列化的类而不是 record：它要作为 JSON 持久化到 {@code scope_refs_json}，
 * 读取复核时要与存储值逐字段比对。
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class AiReportScopeRef {

    /** 资源类型（A03 词表：DATASET/KNOWLEDGE_BASE/REPORT/FILE/TOOL） */
    private String resourceType;

    /** 资源标识（与授权目录里的 resource_key 一致） */
    private String resourceKey;

    /** 保存时该次判定的范围指纹（读取复核用） */
    private String fingerprint;
}
