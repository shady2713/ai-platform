package com.basicframework.module.ai.dal.mysql.theme;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.theme.AiThemeDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 主题修订 Mapper（C04）。 */
@Mapper
public interface AiThemeMapper extends BaseMapperX<AiThemeDO> {

    /** 按对外标识定位（跨应用唯一）。 */
    default AiThemeDO selectByPublicId(String publicId) {
        return selectOne(new LambdaQueryWrapperX<AiThemeDO>().eq(AiThemeDO::getPublicId, publicId));
    }

    /** 按应用 + 修订号定位（同一应用内唯一）。 */
    default AiThemeDO selectByApplicationAndRevision(Long applicationId, Integer revision) {
        return selectOne(new LambdaQueryWrapperX<AiThemeDO>()
                .eq(AiThemeDO::getApplicationId, applicationId)
                .eq(AiThemeDO::getRevision, revision));
    }

    /** 应用内最大修订号（下一修订号由此 +1）。 */
    default Integer selectMaxRevision(Long applicationId) {
        List<AiThemeDO> list = selectList(new LambdaQueryWrapperX<AiThemeDO>()
                .eq(AiThemeDO::getApplicationId, applicationId)
                .orderByDesc(AiThemeDO::getRevision)
                .last("LIMIT 1"));
        AiThemeDO latest = list.isEmpty() ? null : list.get(0);
        return latest == null || latest.getRevision() == null ? 0 : latest.getRevision();
    }

    /** 当前生效修订（同一应用最多一条，由服务层同事务保证）。 */
    default AiThemeDO selectPublished(Long applicationId) {
        return selectOne(new LambdaQueryWrapperX<AiThemeDO>()
                .eq(AiThemeDO::getApplicationId, applicationId)
                .eq(AiThemeDO::getPublicationState, AiThemeDO.STATE_PUBLISHED));
    }

    /** 分页（按修订号倒序，可按应用与发布状态过滤）。 */
    default PageResult<AiThemeDO> selectPage(PageParam pageParam, Long applicationId, String publicationState) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<AiThemeDO>()
                        .eqIfPresent(AiThemeDO::getApplicationId, applicationId)
                        .eqIfPresent(AiThemeDO::getPublicationState, publicationState)
                        .orderByDesc(AiThemeDO::getId));
    }

    /** 乐观锁 CAS。 */
    default int updateWithVersion(AiThemeDO update, Integer expectedVersion) {
        return update(
                update,
                new LambdaUpdateWrapper<AiThemeDO>()
                        .eq(AiThemeDO::getId, update.getId())
                        .eq(AiThemeDO::getVersion, expectedVersion));
    }
}
