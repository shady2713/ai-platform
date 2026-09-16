package com.basicframework.module.system.dal.dataobject.dept;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 部门表
 *
 */
@TableName("system_dept")
@KeySequence("system_dept_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class DeptDO extends SoftDeletableDO {

    public static final Long PARENT_ID_ROOT = 0L;

    /**
     * 部门ID
     */
    @TableId
    private Long id;
    /**
     * 部门名称
     */
    private String name;
    /**
     * 父部门ID
     *
     * 关联 {@link #id}
     */
    private Long parentId;
    /**
     * 显示顺序
     */
    private Integer sort;
    /**
     * 负责人
     *
     * 关联 {@link AdminUserDO#getId()}
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long leaderUserId;
    /**
     * 联系电话
     */
    @ToString.Exclude
    private String phone;
    /**
     * 邮箱
     */
    @ToString.Exclude
    private String email;
    /**
     * 部门状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    private Integer status;
}
