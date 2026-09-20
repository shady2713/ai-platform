package com.basicframework.module.ai.dal.dataobject.dataset;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 语义数据集（D04）：来源对象 + 语义版本序列。
 *
 * <p>{@code sourceObject} 必须是连接器授权白名单内的 {@code schema.table}：
 * 数据集不自己维护"能不能读"，只声明"读哪个对象"，可读性由连接器（D03）保证。
 * 标识与来源在创建后不可修改（报表按数据集 + 版本编号引用，改来源会让历史引用失真）。
 */
@TableName("ai_dataset")
@KeySequence("ai_dataset_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiDatasetDO extends SoftDeletableDO {

    /** 状态：启用 */
    public static final String STATUS_ENABLED = "ENABLED";

    /** 状态：停用 */
    public static final String STATUS_DISABLED = "DISABLED";

    /** 数据集编号 */
    @TableId
    private Long id;

    /** 数据集标识（全局唯一，创建后不可修改） */
    private String code;

    /** 数据集名称 */
    private String name;

    /** 说明 */
    private String description;

    /** 连接器编号（只读数据来源） */
    private Long connectorId;

    /** 来源对象（schema.table，必须在连接器授权白名单内） */
    private String sourceObject;

    /** 状态（ENABLED/DISABLED） */
    private String status;

    /** 最新语义版本号（0 表示尚无版本） */
    private Integer latestVersionNo;

    /** 最近发布的语义版本号（0 表示未发布） */
    private Integer publishedVersionNo;

    /** 乐观锁版本 */
    private Integer version;
}
