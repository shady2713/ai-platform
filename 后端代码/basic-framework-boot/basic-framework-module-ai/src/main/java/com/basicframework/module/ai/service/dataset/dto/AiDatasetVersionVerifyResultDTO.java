package com.basicframework.module.ai.service.dataset.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 版本验证/发布结论（服务层 DTO）：状态 + 漂移结论。
 *
 * <p>只带列名与结论，不带上游数据值；漂移结论可直接展示给配置人员（"哪些列对不上"）。
 */
@Data
@Accessors(chain = true)
public class AiDatasetVersionVerifyResultDTO {

    /** 版本编号 */
    private Long versionId;

    /** 语义版本号 */
    private Integer versionNo;

    /** 版本状态（DRAFT/PUBLISHED） */
    private String status;

    /** 验证状态（UNVERIFIED/VERIFIED/DRIFTED） */
    private String verificationStatus;

    /** 定义引用了但上游缺失的列 */
    private List<String> missingColumns;

    /** 上游存在但类型不再兼容的列 */
    private List<String> typeChangedColumns;

    /** 上游新增（定义未使用）的列 */
    private List<String> addedColumns;

    /** 定义内容哈希 */
    private String schemaHash;

    /** 上游结构哈希（验证基线） */
    private String sourceSchemaHash;

    /** 是否可发布（没有缺失列与类型变化） */
    private boolean publishable;
}
