package com.basicframework.module.system.dal.mysql.dept;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.dept.UserPostDO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserPostMapper extends BaseMapperX<UserPostDO> {

    default List<UserPostDO> selectListByUserId(Long userId) {
        return selectList(UserPostDO::getUserId, userId);
    }

    /** 持有用户行锁后读取当前岗位关系，避免外层事务的旧快照误算关系差量。 */
    default List<UserPostDO> selectListByUserIdForUpdate(Long userId) {
        return selectList(new LambdaQueryWrapperX<UserPostDO>()
                .eq(UserPostDO::getUserId, userId)
                .last("FOR UPDATE"));
    }

    // 以下删除方法均为物理删除：关联表配合 V3 唯一约束 uk_user_id_post_id，
    // 生命周期台账声明为 hard-delete；显式 SQL 同时钉死删除条件，避免基础实体调整改变岗位撤销语义
    @Delete(
            "<script>DELETE FROM system_user_post WHERE user_id = #{userId} AND post_id IN "
                    + "<foreach collection='postIds' item='postId' open='(' separator=',' close=')'>#{postId}</foreach></script>")
    void deleteByUserIdAndPostId(@Param("userId") Long userId, @Param("postIds") Collection<Long> postIds);

    default List<UserPostDO> selectListByPostIds(Collection<Long> postIds) {
        return selectList(UserPostDO::getPostId, postIds);
    }

    @Delete("DELETE FROM system_user_post WHERE user_id = #{userId}")
    void deleteByUserId(@Param("userId") Long userId);
}
