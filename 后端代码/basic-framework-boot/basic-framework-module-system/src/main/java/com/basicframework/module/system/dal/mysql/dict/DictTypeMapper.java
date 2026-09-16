package com.basicframework.module.system.dal.mysql.dict;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.dict.DictTypeDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DictTypeMapper extends BaseMapperX<DictTypeDO> {

    default PageResult<DictTypeDO> selectPage(
            PageParam pageParam, String name, String type, Integer status, LocalDateTime[] createTime) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<DictTypeDO>()
                        .likeIfPresent(DictTypeDO::getName, name)
                        .likeIfPresent(DictTypeDO::getType, type)
                        .eqIfPresent(DictTypeDO::getStatus, status)
                        .betweenIfPresent(DictTypeDO::getCreateTime, createTime)
                        .orderByDesc(DictTypeDO::getId));
    }

    default DictTypeDO selectByType(String type) {
        return selectOne(DictTypeDO::getType, type);
    }

    default DictTypeDO selectByName(String name) {
        return selectOne(DictTypeDO::getName, name);
    }

    @Select("SELECT * FROM system_dict_type WHERE id = #{id} AND deleted = b'0' FOR UPDATE")
    DictTypeDO selectByIdForUpdate(Long id);

    @Select("SELECT * FROM system_dict_type WHERE type = #{type} AND deleted = b'0' FOR SHARE")
    DictTypeDO selectByTypeForShare(String type);

    @Update("UPDATE system_dict_type SET deleted = 1, deleted_time = #{deletedTime} WHERE id = #{id}")
    void updateToDelete(@Param("id") Long id, @Param("deletedTime") LocalDateTime deletedTime);
}
