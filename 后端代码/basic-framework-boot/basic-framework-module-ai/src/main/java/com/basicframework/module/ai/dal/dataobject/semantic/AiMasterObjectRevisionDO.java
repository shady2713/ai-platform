package com.basicframework.module.ai.dal.dataobject.semantic;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 主数据映射版本（Y02）：一个对象的映射快照头。
 *
 * <p>版本是**不可变快照**：{@link #STATUS_DRAFT} 期间可增删映射条目，{@link #STATUS_PUBLISHED}
 * 之后条目与指纹都被冻结——改映射只能新建版本，旧报表按受理时的版本编号永远取回同一份内容
 * （与 D04 数据集版本同一口径）。
 *
 * <p>{@code mapping_fingerprint} 在发布时冻结，读取时重算比对：版本外改动（例如直连数据库改源键）
 * 会让判定以"指纹不符"阻断，而不是悄悄按被改过的内容解释历史报表。
 */
@TableName("ai_master_object_revision")
@KeySequence("ai_master_object_revision_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiMasterObjectRevisionDO extends SoftDeletableDO {

    /** 状态：草稿（可编辑，不可用于判定）。 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：已发布（不可变，判定与报表依据）。 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 版本编号 */
    @TableId
    private Long id;

    /** 统一对象编号 */
    private Long masterObjectId;

    /** 映射版本号（对象内递增） */
    private Long revisionNo;

    /** 状态（DRAFT/PUBLISHED） */
    private String status;

    /** 版本有效期起点（含） */
    private LocalDateTime validFrom;

    /** 版本有效期终点（不含；NULL=长期有效） */
    private LocalDateTime validTo;

    /** 发布时冻结的映射条目数 */
    private Integer entryCount;

    /** 发布时冻结的内容指纹 */
    private String mappingFingerprint;

    /** 草稿创建人（发布人必须不同） */
    private Long createdBy;

    /** 发布人 */
    private Long publishedBy;

    /** 发布时间 */
    private LocalDateTime publishedTime;

    /** 乐观锁版本 */
    private Integer version;
}
