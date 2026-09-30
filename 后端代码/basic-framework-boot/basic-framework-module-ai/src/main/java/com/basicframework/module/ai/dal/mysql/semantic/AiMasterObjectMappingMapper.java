package com.basicframework.module.ai.dal.mysql.semantic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 主数据源键映射 Mapper（Y02）：按版本取内容、按源键反查当前生效对象、草稿期删除 CAS。 */
@Mapper
public interface AiMasterObjectMappingMapper extends BaseMapperX<AiMasterObjectMappingDO> {

    /** 某版本的映射条目（按系统编号 + 实体类型 + 源键排序，保证指纹与展示顺序稳定）。 */
    default List<AiMasterObjectMappingDO> selectByRevision(Long masterObjectId, Long revision) {
        return selectList(new LambdaQueryWrapperX<AiMasterObjectMappingDO>()
                .eq(AiMasterObjectMappingDO::getMasterObjectId, masterObjectId)
                .eq(AiMasterObjectMappingDO::getRevision, revision)
                .orderByAsc(AiMasterObjectMappingDO::getApplicationId)
                .orderByAsc(AiMasterObjectMappingDO::getEntityType)
                .orderByAsc(AiMasterObjectMappingDO::getSourceKey));
    }

    /** 某版本内的单条登记（唯一键定位；重复登记据此拒绝）。 */
    default AiMasterObjectMappingDO selectEntry(
            Long masterObjectId, Long revision, Long applicationId, String entityType, String sourceKey) {
        return selectOne(new LambdaQueryWrapperX<AiMasterObjectMappingDO>()
                .eq(AiMasterObjectMappingDO::getMasterObjectId, masterObjectId)
                .eq(AiMasterObjectMappingDO::getRevision, revision)
                .eq(AiMasterObjectMappingDO::getApplicationId, applicationId)
                .eq(AiMasterObjectMappingDO::getEntityType, entityType)
                .eq(AiMasterObjectMappingDO::getSourceKey, sourceKey)
                .last("limit 1"));
    }

    /** 某版本的条目数（草稿的登记预算与发布摘要据此计算）。 */
    default long countByRevision(Long masterObjectId, Long revision) {
        return selectCount(new LambdaQueryWrapperX<AiMasterObjectMappingDO>()
                .eq(AiMasterObjectMappingDO::getMasterObjectId, masterObjectId)
                .eq(AiMasterObjectMappingDO::getRevision, revision));
    }

    /** 草稿期删除条目（带乐观锁：并发编辑只允许单赢家；已发布版本由服务层拒绝）。 */
    default int deleteEntry(Long id, Integer version) {
        if (id == null || version == null) {
            return 0;
        }
        return delete(new LambdaQueryWrapper<AiMasterObjectMappingDO>()
                .eq(AiMasterObjectMappingDO::getId, id)
                .eq(AiMasterObjectMappingDO::getVersion, version));
    }

    /**
     * 按（系统, 实体类型, 源键）反查**当前已发布版本**里的登记行。
     *
     * <p>这是判定路径的入口：{@code revision = o.current_revision} 把候选限定在"当前已发布的映射"
     * 上（草稿不参与判定），{@code o.deleted = b'0'} 排除已删除对象——本表没有逻辑删除列，
     * 因此逻辑删除条件必须写在 SQL 里而不是依赖框架。
     */
    @Select(
            """
            SELECT m.id, m.master_object_id, m.revision, m.application_id, m.entity_type, m.source_key,
                   m.source_name, m.match_method, m.valid_from, m.valid_to, m.version,
                   m.creator, m.create_time, m.updater, m.update_time
            FROM ai_master_object_mapping m
            JOIN ai_master_object o ON o.id = m.master_object_id AND o.deleted = b'0'
            WHERE m.revision = o.current_revision
              AND m.application_id = #{applicationId}
              AND m.entity_type = #{entityType}
              AND m.source_key = #{sourceKey}
            ORDER BY m.master_object_id
            """)
    List<AiMasterObjectMappingDO> selectCurrentBySourceKey(
            @Param("applicationId") Long applicationId,
            @Param("entityType") String entityType,
            @Param("sourceKey") String sourceKey);
}
