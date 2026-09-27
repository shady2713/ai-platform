package com.basicframework.module.ai.dal.mysql.application;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiApplicationMapper extends BaseMapperX<AiApplicationDO> {

    default AiApplicationDO selectByAppCode(String appCode) {
        return selectOne(AiApplicationDO::getAppCode, appCode);
    }

    /**
     * 行锁：按应用维度串行化"判定 + 占位"（Q07 AT-059）。
     *
     * <p>配额判定必须在同一临界区内完成，应用行是天然的按应用粒度锁点：同一应用的并发申请在此排队，
     * 后到者拿到锁后能看到前者已提交的占位。返回 null 表示该应用行不存在（无行可锁，调用方需退化处理）。
     */
    @Select("SELECT id FROM ai_application WHERE id = #{id} FOR UPDATE")
    Long lockByIdForUpdate(@Param("id") Long id);

    default PageResult<AiApplicationDO> selectPage(PageParam pageParam, String appCode, Boolean enabled) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiApplicationDO>()
                        .likeIfPresent(AiApplicationDO::getAppCode, appCode)
                        .eqIfPresent(AiApplicationDO::getEnabled, enabled)
                        .orderByDesc(AiApplicationDO::getId));
    }

    /** 乐观锁 CAS：仅当数据库中的 version 仍为期望值时才更新（框架未启用乐观锁插件，手工实现）。 */
    default int updateWithVersion(AiApplicationDO update, @Param("expectedVersion") Integer expectedVersion) {
        return update(
                update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiApplicationDO>()
                        .eq(AiApplicationDO::getId, update.getId())
                        .eq(AiApplicationDO::getVersion, expectedVersion));
    }
}
