package com.basicframework.module.system.dal.mysql.dict;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.dict.DictDataDO;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DictDataMapper extends BaseMapperX<DictDataDO> {

    default DictDataDO selectByDictTypeAndValue(String dictType, String value) {
        return selectOne(DictDataDO::getDictType, dictType, DictDataDO::getValue, value);
    }

    default DictDataDO selectByDictTypeAndLabel(String dictType, String label) {
        return selectOne(DictDataDO::getDictType, dictType, DictDataDO::getLabel, label);
    }

    default List<DictDataDO> selectByDictTypeAndValues(String dictType, Collection<String> values) {
        return selectList(new LambdaQueryWrapper<DictDataDO>()
                .eq(DictDataDO::getDictType, dictType)
                .in(DictDataDO::getValue, values));
    }

    default long selectCountByDictType(String dictType) {
        return selectCount(DictDataDO::getDictType, dictType);
    }

    default PageResult<DictDataDO> selectPage(PageParam pageParam, String label, String dictType, Integer status) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<DictDataDO>()
                        .likeIfPresent(DictDataDO::getLabel, label)
                        .eqIfPresent(DictDataDO::getDictType, dictType)
                        .eqIfPresent(DictDataDO::getStatus, status)
                        .orderByDesc(Arrays.asList(DictDataDO::getDictType, DictDataDO::getSort)));
    }

    default List<DictDataDO> selectListByStatusAndDictType(Integer status, String dictType) {
        return selectList(new LambdaQueryWrapperX<DictDataDO>()
                .eqIfPresent(DictDataDO::getStatus, status)
                .eqIfPresent(DictDataDO::getDictType, dictType));
    }

    @Select("SELECT * FROM system_dict_data WHERE id = #{id} AND deleted = b'0' FOR UPDATE")
    DictDataDO selectByIdForUpdate(Long id);

    @Select("SELECT COUNT(*) FROM system_dict_data WHERE dict_type = #{dictType}")
    long selectPhysicalCountByDictType(String dictType);

    @Select(
            """
            SELECT COUNT(*)
            FROM system_dict_data data
            LEFT JOIN system_dict_type type
              ON type.type = data.dict_type AND type.deleted = b'0'
            WHERE data.deleted = b'0'
              AND type.id IS NULL
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    int selectOrphanDictTypeCount();
}
