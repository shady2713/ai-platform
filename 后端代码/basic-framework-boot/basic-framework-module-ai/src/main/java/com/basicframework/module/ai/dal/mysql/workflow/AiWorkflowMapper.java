package com.basicframework.module.ai.dal.mysql.workflow;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.workflow.AiWorkflowDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.util.StringUtils;

/** 流程定义 Mapper（X08）：应用内标识唯一、按应用与状态分页、手工乐观锁 CAS。 */
@Mapper
public interface AiWorkflowMapper extends BaseMapperX<AiWorkflowDO> {

    /** 按应用 + 标识定位（应用内唯一）。 */
    default AiWorkflowDO selectByCode(Long applicationId, String code) {
        return selectOne(new LambdaQueryWrapperX<AiWorkflowDO>()
                .eq(AiWorkflowDO::getApplicationId, applicationId)
                .eq(AiWorkflowDO::getCode, code));
    }

    /** 分页（按应用过滤，标识/名称模糊、状态精确；按编号倒序）。 */
    default PageResult<AiWorkflowDO> selectPage(PageParam pageParam, Long applicationId, String code, String status) {
        LambdaQueryWrapperX<AiWorkflowDO> wrapper = new LambdaQueryWrapperX<AiWorkflowDO>()
                .eqIfPresent(AiWorkflowDO::getApplicationId, applicationId)
                .eqIfPresent(AiWorkflowDO::getStatus, status)
                .orderByDesc(AiWorkflowDO::getId);
        if (StringUtils.hasText(code)) {
            wrapper.and(inner -> inner.like(AiWorkflowDO::getCode, code).or().like(AiWorkflowDO::getName, code));
        }
        return selectPage(pageParam, wrapper);
    }

    /** 乐观锁 CAS：仅当数据库中的 version 仍为期望值时才更新（框架未启用乐观锁插件，手工实现）。 */
    default int updateWithVersion(AiWorkflowDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiWorkflowDO>()
                        .eq(AiWorkflowDO::getId, update.getId())
                        .eq(AiWorkflowDO::getVersion, expectedVersion));
    }
}
