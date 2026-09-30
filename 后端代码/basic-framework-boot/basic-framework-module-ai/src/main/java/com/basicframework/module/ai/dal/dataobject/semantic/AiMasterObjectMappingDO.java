package com.basicframework.module.ai.dal.dataobject.semantic;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 主数据源键映射条目（Y02）：{@code (来源系统, 实体类型, 源键)} → 企业统一对象。
 *
 * <p>为什么这张表**没有**逻辑删除列：条目是**版本内容**而不是独立事实。草稿期的增删是内容编辑，
 * 物理删除后可以重新登记同一源键（逻辑删除会让唯一键被历史行占住，撤销后无法重新登记——
 * 与 Y01 联邦映射"重提交必须复用同一行"是同一类陷阱的两面）；发布后的条目随版本冻结，永不删除，
 * 只能通过新版本表达变更。
 *
 * <p>{@code source_name} 只用于展示：判定路径从不读取它，"跨系统同名"因此在结构上不可能被合并。
 */
@TableName("ai_master_object_mapping")
@KeySequence("ai_master_object_mapping_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiMasterObjectMappingDO extends BaseDO {

    /** 源键映射编号 */
    @TableId
    private Long id;

    /** 统一对象编号 */
    private Long masterObjectId;

    /** 所属映射版本 */
    private Long revision;

    /** 来源系统（接入应用）编号 */
    private Long applicationId;

    /** 该系统中的实体类型 */
    private String entityType;

    /** 该系统中的业务主键（显式登记事实） */
    private String sourceKey;

    /** 展示名（不参与判定） */
    private String sourceName;

    /** 匹配方式（MANUAL/TRUSTED_FEED） */
    private String matchMethod;

    /** 源键有效期起点（含） */
    private LocalDateTime validFrom;

    /** 源键有效期终点（不含；NULL=长期有效） */
    private LocalDateTime validTo;

    /** 乐观锁版本（草稿期删除 CAS） */
    private Integer version;
}
