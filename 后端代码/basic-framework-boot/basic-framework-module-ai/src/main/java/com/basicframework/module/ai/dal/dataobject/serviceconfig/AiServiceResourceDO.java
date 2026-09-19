package com.basicframework.module.ai.dal.dataobject.serviceconfig;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** AI 服务资源绑定（S01）：releaseId 为空表示草稿绑定；发布时固化到对应版本（S02）。 */
@TableName("ai_service_resource")
@KeySequence("ai_service_resource_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiServiceResourceDO extends SoftDeletableDO {

    /** 状态：有效 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已解绑 */
    public static final String STATUS_RELEASED = "RELEASED";

    /** 绑定编号 */
    @TableId
    private Long id;

    /** 服务编号 */
    private Long serviceId;

    /** 发布版本编号（空表示草稿绑定） */
    private Long releaseId;

    /** 资源类型 */
    private String resourceType;

    /** 资源标识 */
    private String resourceKey;

    /** 需要的动作（逗号分隔） */
    private String actions;

    /** 状态（ACTIVE/RELEASED） */
    private String status;

    /** 乐观锁版本 */
    private Integer version;
}
