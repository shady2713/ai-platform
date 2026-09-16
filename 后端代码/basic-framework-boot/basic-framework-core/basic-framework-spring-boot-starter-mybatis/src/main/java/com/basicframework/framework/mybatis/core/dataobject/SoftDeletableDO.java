package com.basicframework.framework.mybatis.core.dataobject;

import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 显式采用 MyBatis Plus 逻辑删除的实体基类。
 *
 * <p>只有在数据生命周期台账登记为 soft-delete 的聚合才能继承本类；通用 {@link BaseDO}
 * 不拥有删除策略。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class SoftDeletableDO extends BaseDO {

    /** 是否删除。 */
    @TableLogic
    private Boolean deleted;
}
