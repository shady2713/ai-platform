package com.basicframework.module.system.dal.dataobject.permission;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import com.basicframework.module.system.enums.permission.DataScopeEnum;
import com.basicframework.module.system.enums.permission.RoleTypeEnum;
import com.mzt.logapi.starter.annotation.DiffLogField;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色 DO
 *
 */
@TableName(value = "system_role", autoResultMap = true)
@KeySequence("system_role_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class RoleDO extends SoftDeletableDO {

    /**
     * 角色ID
     */
    @TableId
    private Long id;
    /**
     * 角色名称
     */
    @DiffLogField(name = "角色名称")
    private String name;
    /**
     * 角色标识
     *
     * 枚举
     */
    @DiffLogField(name = "角色标志")
    private String code;
    /**
     * 角色排序
     */
    @DiffLogField(name = "显示顺序")
    private Integer sort;
    /**
     * 角色状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    @DiffLogField(name = "状态")
    private Integer status;
    /**
     * 角色类型
     *
     * 枚举 {@link RoleTypeEnum}
     */
    private Integer type;
    /**
     * 备注
     */
    @DiffLogField(name = "备注")
    private String remark;

    /**
     * 数据范围
     *
     * 枚举 {@link DataScopeEnum}
     */
    private Integer dataScope;
    /**
     * 数据范围(指定部门数组)
     *
     * 适用于 {@link #dataScope} 的值为 {@link DataScopeEnum#DEPT_CUSTOM} 时
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Set<Long> dataScopeDeptIds;
}
