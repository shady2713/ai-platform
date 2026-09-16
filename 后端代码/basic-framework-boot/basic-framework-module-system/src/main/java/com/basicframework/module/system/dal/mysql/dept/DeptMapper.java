package com.basicframework.module.system.dal.mysql.dept;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.dept.DeptDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeptMapper extends BaseMapperX<DeptDO> {

    default List<DeptDO> selectList(String name, Integer status) {
        return selectList(new LambdaQueryWrapperX<DeptDO>()
                .likeIfPresent(DeptDO::getName, name)
                .eqIfPresent(DeptDO::getStatus, status));
    }

    default DeptDO selectByParentIdAndName(Long parentId, String name) {
        return selectOne(DeptDO::getParentId, parentId, DeptDO::getName, name);
    }

    default Long selectCountByParentId(Long parentId) {
        return selectCount(DeptDO::getParentId, parentId);
    }

    default List<DeptDO> selectListByLeaderUserId(Long id) {
        return selectList(DeptDO::getLeaderUserId, id);
    }

    default DeptDO selectByIdForShare(Long id) {
        return selectOne(new LambdaQueryWrapperX<DeptDO>().eq(DeptDO::getId, id).last("FOR SHARE"));
    }

    default DeptDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<DeptDO>().eq(DeptDO::getId, id).last("FOR UPDATE"));
    }

    default int clearLeaderUserId(Long userId) {
        return update(
                new DeptDO().setLeaderUserId(null),
                new LambdaQueryWrapperX<DeptDO>().eq(DeptDO::getLeaderUserId, userId));
    }

    @Select("SELECT id, parent_id FROM system_dept WHERE deleted = b'0'")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<DeptDO> selectListForIntegrityAudit();

    @Select(
            """
            SELECT COUNT(*)
            FROM system_dept d
            LEFT JOIN system_users u ON u.id = d.leader_user_id AND u.deleted = b'0'
            WHERE d.deleted = b'0' AND d.leader_user_id IS NOT NULL AND u.id IS NULL
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    int selectOrphanLeaderUserCount();
}
