package com.basicframework.module.ai.dal.dataobject.application;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 应用（A01）。
 *
 * <p>应用是对接方的身份来源：{@code appCode} 全局唯一且创建后不可修改（改名会让已对接方静默失效），
 * {@code origins} 保存**精确 Origin 列表**（JSON 数组文本，已归一化，禁止路径与通配）。
 */
@TableName("ai_application")
@KeySequence("ai_application_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiApplicationDO extends SoftDeletableDO {

    /** 应用编号 */
    @TableId
    private Long id;

    /** 应用标识（全局唯一，创建后不可修改） */
    private String appCode;

    /** 应用名称 */
    private String name;

    /** 应用说明 */
    private String description;

    /** 精确 Origin 列表（JSON 数组文本，已归一化） */
    private String origins;

    /** 是否启用 */
    private Boolean enabled;

    /** 乐观锁版本（手工 CAS） */
    private Integer version;
}
