package com.basicframework.module.ai.dal.dataobject.knowledge;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 知识库（K02）：共享/应用专用 + 嵌入模型与维度 + 当前索引代。
 *
 * <p>为什么把嵌入模型与维度放在知识库上：维度是**索引的物理约束**（K01 结论：集合维度创建后不可变），
 * 换模型必须换索引代，禁止把不同模型的向量写进同一集合（AT-029）。因此这两列在创建后不可修改，
 * 只能新建知识库或换代。
 *
 * <p>"允许应用"不落本表：应用可见性是 A03 授权目录（资源类型 KNOWLEDGE_BASE，资源标识 = {@code code}）的职责，
 * 本表只声明"共享还是应用专用"，避免出现第二套授权事实。
 */
@TableName("ai_knowledge_base")
@KeySequence("ai_knowledge_base_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiKnowledgeBaseDO extends SoftDeletableDO {

    /** 可见性：共享（跨应用，靠显式授权放开）。 */
    public static final String VISIBILITY_SHARED = "SHARED";

    /** 可见性：应用专用（必须绑定所属应用）。 */
    public static final String VISIBILITY_APPLICATION = "APPLICATION";

    /** 状态：启用。 */
    public static final String STATUS_ENABLED = "ENABLED";

    /** 状态：停用（停用后不接受新入库与索引换代）。 */
    public static final String STATUS_DISABLED = "DISABLED";

    /** 知识库编号 */
    @TableId
    private Long id;

    /** 知识库标识（全局唯一，创建后不可修改；A03 授权目录的资源标识） */
    private String code;

    /** 知识库名称 */
    private String name;

    /** 说明 */
    private String description;

    /** 可见性（SHARED/APPLICATION） */
    private String visibility;

    /** 所属应用编号（应用专用必填，共享为空） */
    private Long ownerApplicationId;

    /** 管理者用户编号（仅展示与联系；鉴权一律走权限码与授权目录） */
    private Long managerUserId;

    /** 嵌入模型标识（创建后不可修改） */
    private String embeddingModel;

    /** 嵌入维度（创建后不可修改；索引代维度必须与它一致） */
    private Integer embeddingDimension;

    /** 当前生效的索引代（0 表示尚无可用索引） */
    private Integer activeGenerationNo;

    /** 保留策略（天） */
    private Integer retentionDays;

    /** 状态（ENABLED/DISABLED） */
    private String status;

    /** 乐观锁版本 */
    private Integer version;
}
