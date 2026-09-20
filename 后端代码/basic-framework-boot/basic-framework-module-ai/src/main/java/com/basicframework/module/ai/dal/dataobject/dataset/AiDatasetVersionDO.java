package com.basicframework.module.ai.dal.dataobject.dataset;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 语义数据集版本（D04）：不可变语义快照。
 *
 * <p>版本发布后 {@code definitionJson} 与 {@code schemaHash} 不得再改（只能新建版本），
 * 因此旧报表按版本编号引用时永远能取回"当时那份语义"。漂移只更新验证状态与漂移结论，
 * 不改定义本身——定义是引用快照，漂移是"这份快照还能不能执行"的结论。
 */
@TableName("ai_dataset_version")
@KeySequence("ai_dataset_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiDatasetVersionDO extends SoftDeletableDO {

    /** 状态：草稿 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：已发布（不可变） */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 验证状态：未验证 */
    public static final String VERIFICATION_UNVERIFIED = "UNVERIFIED";

    /** 验证状态：已验证（定义与上游一致且类型兼容） */
    public static final String VERIFICATION_VERIFIED = "VERIFIED";

    /** 验证状态：已漂移（上游结构与定义不一致，待处理） */
    public static final String VERIFICATION_DRIFTED = "DRIFTED";

    /** 版本编号 */
    @TableId
    private Long id;

    /** 数据集编号 */
    private Long datasetId;

    /** 语义版本号（数据集内递增，发布后不可变） */
    private Integer versionNo;

    /** 状态（DRAFT/PUBLISHED） */
    private String status;

    /** 语义定义（规范化 JSON，含字段/指标/维度/时间/单位/粒度/权限策略） */
    private String definitionJson;

    /** 定义内容哈希 */
    private String schemaHash;

    /** 验证通过时的上游结构哈希（漂移基线） */
    private String sourceSchemaHash;

    /** 验证状态（UNVERIFIED/VERIFIED/DRIFTED） */
    private String verificationStatus;

    /** 最近一次漂移结论（列名与结论，不含上游数据） */
    private String driftJson;

    /** 最近一次验证时间 */
    private java.time.LocalDateTime verifiedAt;

    /** 发布时间 */
    private java.time.LocalDateTime publishedAt;

    /** 乐观锁版本 */
    private Integer version;
}
